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
import java.util.concurrent.atomic.AtomicReference;
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
    private final AtomicReference<Runnable> onChange = new AtomicReference<>();

    // Card presence state (guarded by lock)
    private byte[] activeAtr;                    // non-null = card is "in the reader"
    private Iterator<BIBO> biboQueue;            // queue mode: pops next BIBO per session
    private Function<String, BIBO> biboFactory;  // factory mode: creates BIBO per session

    // Active session state (guarded by lock)
    // BIBO is created lazily on first transmit(), not on connect() -
    // this avoids creating sessions during listPCSC probe cycles (connect + getATR + disconnect)
    private BIBO activeBibo;
    private String connectProtocol;
    private SynthesizedCard activeCard;
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
            activeCard = null;
            biboQueue = null;
            biboFactory = null;
            connectProtocol = null;
            generation++;
            lock.notifyAll();
            fireOnChange();
        }
    }

    void setOnChange(Runnable callback) {
        onChange.set(callback);
    }

    // Card is "in the reader" when there's an active session, a factory, or queued BIBOs
    private boolean cardPresent() {
        return activeBibo != null
                || biboFactory != null
                || (biboQueue != null && biboQueue.hasNext());
    }

    private void fireOnChange() {
        var cb = onChange.get();
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
        synchronized (lock) {
            if (!cardPresent()) {
                throw new CardNotPresentException("Card not present!");
            }
            if (activeCard == null) {
                connectProtocol = s;
                activeCard = new SynthesizedCard(activeAtr.clone());
            }
            return activeCard;
        }
    }

    @Override
    public boolean isCardPresent() {
        synchronized (lock) {
            return cardPresent();
        }
    }

    // Waits until condition is true or timeout expires; caller must NOT hold lock
    private boolean waitForCondition(long timeout, BooleanSupplier condition) {
        synchronized (lock) {
            long deadline = timeout > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout) : 0;
            while (!condition.getAsBoolean()) {
                long waitMs = timeout == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (timeout > 0 && waitMs <= 0) {
                    return false;
                }
                try {
                    lock.wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
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

    // Resolves the BIBO lazily -called on first transmit(), never on connect()
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
            return channel;
        }

        @Override
        public CardChannel openLogicalChannel() throws CardException {
            checkExclusive();
            var bibo = resolveBibo();
            try {
                // MANAGE CHANNEL OPEN: P1=00 (open), P2=00 (auto-assign), Le=01
                var cmd = new apdu4j.core.CommandAPDU(0x00, 0x70, 0x00, 0x00, 1);
                var r = new apdu4j.core.ResponseAPDU(bibo.transceive(cmd.getBytes()));
                if (r.getSW() != 0x9000) {
                    throw new CardException("MANAGE CHANNEL failed: SW=%04X".formatted(r.getSW()));
                }
                return new SynthesizedChannel(r.getData()[0] & 0xFF);
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

        @Override
        public void beginExclusive() throws CardException {
            logger.trace("Card#beginExclusive()");
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
            checkExclusive();
            synchronized (lock) {
                activeCard = null;
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
                return channelNumber;
            }

            @Override
            public ResponseAPDU transmit(CommandAPDU commandAPDU) throws CardException {
                Objects.requireNonNull(commandAPDU, "command APDU");
                checkExclusive();
                var bibo = resolveBibo();
                logger.trace("transmit({})", HexUtils.bin2hex(commandAPDU.getBytes()));
                try {
                    return new ResponseAPDU(bibo.transceive(commandAPDU.getBytes()));
                } catch (BIBOException e) {
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
                checkExclusive();
                var bibo = resolveBibo();
                byte[] cmd = new byte[command.remaining()];
                command.get(cmd);
                logger.trace("transmit({})", HexUtils.bin2hex(cmd));
                try {
                    var result = bibo.transceive(cmd);
                    response.put(result);
                    return result.length;
                } catch (BIBOException e) {
                    throw new CardException(e.getMessage(), e);
                }
            }

            @Override
            public void close() throws CardException {
                // Basic channel is never closed per javax.smartcardio spec
                if (channelNumber == 0) {
                    return;
                }
                checkExclusive();
                var bibo = resolveBibo();
                try {
                    // MANAGE CHANNEL CLOSE: INS=70, P1=80 (close), P2=channel number
                    var cmd = new apdu4j.core.CommandAPDU(0x00, 0x70, 0x80, channelNumber);
                    bibo.transceive(cmd.getBytes());
                } catch (BIBOException e) {
                    throw new CardException(e.getMessage(), e);
                }
            }
        }
    }
}
