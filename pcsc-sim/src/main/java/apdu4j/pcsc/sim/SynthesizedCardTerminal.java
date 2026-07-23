// SPDX-FileCopyrightText: 2020 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc.sim;

import apdu4j.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.*;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public final class SynthesizedCardTerminal extends CardTerminal {

    private static final Logger logger = LoggerFactory.getLogger(SynthesizedCardTerminal.class);

    private static final byte[] DEFAULT_ATR = HexUtils.hex2bin("3B00");

    public static byte[] defaultAtr() {
        return DEFAULT_ATR.clone();
    }

    private final String name;
    private final String protocol;
    private final Object lock = new Object();
    private volatile Runnable onChange;

    // Card presence state (guarded by lock)
    private byte[] activeAtr;                    // non-null = card is "in the reader"
    private Iterator<BIBO> biboQueue;            // queue mode: pops next BIBO per session
    private Function<String, BIBO> biboFactory;  // factory mode: creates BIBO per session

    // Active session state (guarded by lock)
    private BIBO activeBibo;
    private String connectProtocol;
    // Per-thread to isolate each PC/SC context's handle.
    private final ThreadLocal<SynthesizedCard> activeCard = new ThreadLocal<>();
    // Guards async present() callbacks against stale state after yank()
    private long generation;

    public SynthesizedCardTerminal(String name) {
        this(name, "T=1");
    }

    public SynthesizedCardTerminal(String name, String protocol) {
        this.name = name;
        this.protocol = protocol;
    }

    public static SynthesizedCardTerminal replay(InputStream in) {
        var dump = DumpFormat.parse(in);
        var t = new SynthesizedCardTerminal("APDUReplay terminal 0", dump.protocol());
        t.present(MockBIBO.fromDump(dump), dump.atr());
        return t;
    }

    // --- Card presentation API ---

    // Single BIBO: one connect/transmit cycle, then card disappears
    public void present(BIBO bibo) {
        present(bibo, defaultAtr());
    }

    public void present(BIBO bibo, byte[] atr) {
        present(List.of(bibo), atr);
    }

    // Queue: each connect cycle consumes next BIBO; card gone when queue empty
    public void present(List<BIBO> bibos, byte[] atr) {
        if (bibos.isEmpty()) {
            throw new IllegalArgumentException("At least one BIBO required");
        }
        synchronized (lock) {
            if (activeAtr != null) {
                throw new IllegalStateException("Card already present");
            }
            activeAtr = atr.clone();
            biboQueue = bibos.iterator();
            biboFactory = null;
            generation++;
            lock.notifyAll();
            fireOnChange();
        }
    }

    // Factory: creates fresh BIBO per connect, card stays until yank()
    // Distinct name from present() because BIBO is a functional interface,
    // so a Function<String, BIBO> lambda would otherwise be ambiguous with present(BIBO, byte[]).
    public void presentFactory(Function<String, BIBO> factory, byte[] atr) {
        synchronized (lock) {
            if (activeAtr != null) {
                throw new IllegalStateException("Card already present");
            }
            activeAtr = atr.clone();
            biboFactory = factory;
            biboQueue = null;
            generation++;
            lock.notifyAll();
            fireOnChange();
        }
    }

    // Async single BIBO: card becomes present when future completes
    public void present(CompletableFuture<BIBO> futureBibo, byte[] atr) {
        presentWhenComplete(futureBibo, atr, bibo -> List.of(bibo).iterator());
    }

    // Async multi-BIBO: each connect cycle consumes next BIBO; card auto-yanks when depleted
    public void presentAsync(CompletableFuture<List<BIBO>> futureBibos, byte[] atr) {
        presentWhenComplete(futureBibos, atr, List::iterator);
    }

    // Shared async present: reserves ATR, wires future to set queue on completion
    private <T> void presentWhenComplete(CompletableFuture<T> future, byte[] atr, Function<T, Iterator<BIBO>> toIterator) {
        long myGen;
        synchronized (lock) {
            if (activeAtr != null) {
                throw new IllegalStateException("Card already present");
            }
            activeAtr = atr.clone();
            generation++;
            myGen = generation;
            lock.notifyAll(); // wake waiters so they re-check with the loop
        }
        future.thenAccept(value -> {
            synchronized (lock) {
                if (generation != myGen) {
                    return; // stale: yank() happened since present()
                }
                biboQueue = toIterator.apply(value);
                lock.notifyAll();
                fireOnChange();
            }
        }).exceptionally(ex -> {
            synchronized (lock) {
                if (generation != myGen) {
                    return null;
                }
                activeAtr = null;
                lock.notifyAll();
                fireOnChange();
            }
            return null;
        });
    }

    public void yank() {
        synchronized (lock) {
            if (activeBibo != null) {
                try {
                    activeBibo.close();
                } catch (Exception ignored) {
                }
            }
            activeBibo = null;
            activeAtr = null;
            activeCard.remove();
            biboQueue = null;
            biboFactory = null;
            connectProtocol = null;
            generation++;
            lock.notifyAll();
            fireOnChange();
        }
    }

    void setOnChange(Runnable callback) {
        this.onChange = callback;
    }

    // Card is "in the reader" when there's an active session, a factory, or queued BIBOs
    private boolean cardPresent() {
        return activeBibo != null
                || biboFactory != null
                || (biboQueue != null && biboQueue.hasNext());
    }

    private void fireOnChange() {
        var cb = onChange;
        if (cb != null) {
            cb.run();
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Card connect(String s) throws CardException {
        Objects.requireNonNull(s, "protocol");
        logger.trace("connect({})", s);
        if (!s.equals("*") && !s.equalsIgnoreCase("T=0") && !s.equalsIgnoreCase("T=1") && !s.equalsIgnoreCase("T=CL")) {
            throw new IllegalArgumentException("Unsupported protocol: " + s);
        }
        synchronized (lock) {
            if (!cardPresent()) {
                throw new CardNotPresentException("Card not present!");
            }
            // "*" takes the terminal's protocol; a specific request must be the one this terminal establishes.
            if (!s.equals("*") && !s.equalsIgnoreCase(protocol)) {
                throw new CardException("Cannot connect with protocol %s: terminal uses %s".formatted(s, protocol));
            }
            var card = activeCard.get();
            // The same context reconnects to the same handle per contract.
            if (card == null || card.disposed || card.bornAt != generation) {
                connectProtocol = protocol;
                card = new SynthesizedCard(activeAtr.clone());
                activeCard.set(card);
            }
            return card;
        }
    }

    @Override
    public boolean isCardPresent() {
        synchronized (lock) {
            return cardPresent();
        }
    }

    // Waits until condition is true or timeout expires; caller must NOT hold lock
    private boolean waitForCondition(long timeout, BooleanSupplier condition) throws CardException {
        synchronized (lock) {
            long deadline = timeout > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout) : 0;
            while (!condition.getAsBoolean()) {
                long waitMs;
                if (timeout == 0) {
                    waitMs = 0;
                } else {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return false;
                    }
                    // Round a sub-millisecond remainder up to a full millisecond of wait.
                    waitMs = (remaining + 999_999) / 1_000_000;
                }
                try {
                    lock.wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CardException("Interrupted", e);
                }
            }
            return true;
        }
    }

    @Override
    public boolean waitForCardPresent(long l) throws CardException {
        if (l < 0) {
            throw new IllegalArgumentException("Timeout must not be negative");
        }
        logger.debug("waitForCardPresent({})", l);
        return waitForCondition(l, this::cardPresent);
    }

    @Override
    public boolean waitForCardAbsent(long l) throws CardException {
        if (l < 0) {
            throw new IllegalArgumentException("Timeout must not be negative");
        }
        logger.debug("waitForCardAbsent({})", l);
        return waitForCondition(l, () -> !cardPresent());
    }

    // Lazy to keep probe cycles sessionless.
    private BIBO resolveBibo() throws CardException {
        synchronized (lock) {
            if (activeBibo != null) {
                return activeBibo;
            }
            try {
                if (biboFactory != null) {
                    activeBibo = biboFactory.apply(connectProtocol);
                } else if (biboQueue != null && biboQueue.hasNext()) {
                    activeBibo = biboQueue.next();
                } else {
                    throw new CardException("No BIBO available");
                }
            } catch (BIBOException e) {
                throw new CardException("Failed to create session", e);
            }
            return activeBibo;
        }
    }

    class SynthesizedCard extends Card {
        private final ATR atr;
        private final SynthesizedChannel channel = new SynthesizedChannel();
        private volatile Thread exclusiveThread;
        // A later yank() or present() strands this card.
        private final long bornAt = generation;
        // A released handle must not resolve the next session.
        private volatile boolean disposed;

        SynthesizedCard(byte[] atr) {
            this.atr = new ATR(atr);
        }

        @Override
        public ATR getATR() {
            return atr;
        }

        @Override
        public String getProtocol() {
            return protocol;
        }

        @Override
        public CardChannel getBasicChannel() {
            checkDisposed();
            return channel;
        }

        @Override
        public CardChannel openLogicalChannel() throws CardException {
            checkFresh();
            checkExclusive();
            var bibo = resolveBibo();
            try {
                // MANAGE CHANNEL OPEN: P1=00 (open), P2=00 (auto-assign), Le=01
                var cmd = new CommandAPDU(0x00, 0x70, 0x00, 0x00, 1);
                var r = new ResponseAPDU(bibo.transceive(cmd.getBytes()));
                if (r.getSW() != 0x9000) {
                    throw new CardException("MANAGE CHANNEL failed: SW=%04X".formatted(r.getSW()));
                }
                var data = r.getData();
                if (data.length < 1) {
                    throw new CardException("MANAGE CHANNEL returned no channel id");
                }
                int id = data[0] & 0xFF;
                // A channel above ISO 7816-4's 1..19 overflows the CLA.
                if (id < 1 || id > 19) {
                    throw new CardException("MANAGE CHANNEL returned invalid channel id: " + id);
                }
                return new SynthesizedChannel(id);
            } catch (BIBOException | IllegalArgumentException e) {
                throw new CardException(e.getMessage(), e);
            }
        }

        void checkExclusive() throws CardException {
            var exclusive = exclusiveThread;
            if (exclusive != null && exclusive != Thread.currentThread()) {
                throw new CardException("Exclusive access established by another Thread");
            }
        }

        // A caller-disposed handle throws IllegalStateException on touch.
        void checkDisposed() {
            if (disposed) {
                throw new IllegalStateException("Card has been disconnected");
            }
        }

        // A yanked card fails as CardException.
        void checkFresh() throws CardException {
            checkDisposed();
            synchronized (lock) {
                if (bornAt != generation) {
                    throw new CardException("Card has been removed");
                }
            }
        }

        @Override
        public void beginExclusive() throws CardException {
            logger.trace("Card#beginExclusive()");
            checkFresh();
            synchronized (lock) {
                if (exclusiveThread != null) {
                    throw new CardException("Exclusive access has already been assigned");
                }
                exclusiveThread = Thread.currentThread();
            }
        }

        @Override
        public void endExclusive() throws CardException {
            logger.trace("Card#endExclusive()");
            checkDisposed();
            synchronized (lock) {
                if (exclusiveThread != Thread.currentThread()) {
                    throw new IllegalStateException("endExclusive() called without matching beginExclusive()");
                }
                exclusiveThread = null;
            }
        }

        @Override
        public byte[] transmitControlCommand(int i, byte[] bytes) throws CardException {
            throw new CardException("transmitControlCommand is not supported");
        }

        @Override
        public void disconnect(boolean reset) throws CardException {
            logger.trace("Card#disconnect({})", reset);
            // Repeat disconnect returns per contract.
            if (disposed) {
                return;
            }
            checkExclusive();
            synchronized (lock) {
                activeCard.remove();
                disposed = true;
                if (reset) {
                    // Close current session BIBO
                    if (activeBibo != null) {
                        try {
                            activeBibo.close();
                        } catch (Exception ignored) {
                        }
                        activeBibo = null;
                    }
                    connectProtocol = null;
                    // Queue mode: if no more BIBOs, card disappears (auto-yank)
                    if (biboFactory == null && (biboQueue == null || !biboQueue.hasNext())) {
                        activeAtr = null;
                        biboQueue = null;
                    }
                    lock.notifyAll();
                    fireOnChange();
                }
            }
        }

        @Override
        public String toString() {
            return "Card protocol: %s atr: %s".formatted(protocol, HexUtils.bin2hex(atr.getBytes()));
        }

        class SynthesizedChannel extends CardChannel {
            private final int channelNumber;
            private volatile boolean closed;

            SynthesizedChannel() {
                this(0);
            }

            SynthesizedChannel(int channelNumber) {
                this.channelNumber = channelNumber;
            }

            @Override
            public Card getCard() {
                return SynthesizedCard.this;
            }

            @Override
            public int getChannelNumber() {
                if (closed) {
                    throw new IllegalStateException("Logical channel has been closed");
                }
                checkDisposed();
                return channelNumber;
            }

            // Encodes the channel number into CLA per ISO 7816-4.
            private byte[] withChannel(byte[] apdu) {
                if (channelNumber == 0 || apdu.length == 0 || (apdu[0] & 0x80) != 0) {
                    return apdu;
                }
                int cla = apdu[0] & 0xFF;
                if (channelNumber < 4) {
                    cla = (cla & 0xBC) | channelNumber;
                } else {
                    cla = (cla & 0xB0) | 0x40 | (channelNumber - 4);
                }
                apdu[0] = (byte) cla;
                return apdu;
            }

            // MANAGE CHANNEL belongs to the channel lifecycle API.
            private static boolean isManageChannel(byte[] apdu) {
                return apdu.length >= 2 && (apdu[0] & 0x80) == 0 && (apdu[1] & 0xFF) == 0x70;
            }

            // Returns the raw response without validating the status word.
            private byte[] exchange(byte[] cmd) throws CardException {
                if (closed) {
                    throw new IllegalStateException("Logical channel has been closed");
                }
                if (isManageChannel(cmd)) {
                    throw new IllegalArgumentException("MANAGE CHANNEL must go through openLogicalChannel/close");
                }
                checkFresh();
                checkExclusive();
                var bibo = resolveBibo();
                var apdu = withChannel(cmd);
                logger.trace("transmit({})", HexUtils.bin2hex(apdu));
                try {
                    return bibo.transceive(apdu);
                } catch (BIBOException | IllegalArgumentException e) {
                    throw new CardException(e.getMessage(), e);
                }
            }

            @Override
            public ResponseAPDU transmit(CommandAPDU commandAPDU) throws CardException {
                Objects.requireNonNull(commandAPDU, "command APDU");
                byte[] result = exchange(commandAPDU.getBytes());
                try {
                    return new ResponseAPDU(result);
                } catch (IllegalArgumentException e) {
                    throw new CardException(e.getMessage(), e);
                }
            }

            @Override
            public int transmit(ByteBuffer command, ByteBuffer response) throws CardException {
                Objects.requireNonNull(command, "command buffer");
                Objects.requireNonNull(response, "response buffer");
                if (response.isReadOnly()) {
                    throw new ReadOnlyBufferException();
                }
                if (command == response) {
                    throw new IllegalArgumentException("command and response must not be the same object");
                }
                if (response.remaining() < 258) {
                    throw new IllegalArgumentException("Response buffer must hold at least 258 bytes");
                }
                byte[] cmd = new byte[command.remaining()];
                command.get(cmd);
                byte[] result = exchange(cmd);
                response.put(result);
                return result.length;
            }

            @Override
            public void close() throws CardException {
                // The basic channel cannot be closed per javax.smartcardio spec.
                if (channelNumber == 0) {
                    throw new IllegalStateException("Cannot close basic logical channel");
                }
                if (closed) {
                    return;
                }
                checkFresh();
                checkExclusive();
                var bibo = resolveBibo();
                try {
                    // MANAGE CHANNEL CLOSE carries the channel in P2 and the CLA.
                    var cmd = new CommandAPDU(0x00, 0x70, 0x80, channelNumber);
                    var r = new ResponseAPDU(bibo.transceive(withChannel(cmd.getBytes())));
                    if (r.getSW() != 0x9000) {
                        throw new CardException("MANAGE CHANNEL CLOSE failed: SW=%04X".formatted(r.getSW()));
                    }
                } catch (BIBOException e) {
                    throw new CardException(e.getMessage(), e);
                } finally {
                    // Close retires the channel regardless of outcome.
                    closed = true;
                }
            }
        }
    }
}
