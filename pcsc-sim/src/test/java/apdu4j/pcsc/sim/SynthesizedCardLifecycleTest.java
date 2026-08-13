// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc.sim;

import apdu4j.core.BIBO;
import apdu4j.core.BIBOException;
import apdu4j.core.HexUtils;
import apdu4j.core.MockBIBO;
import org.testng.annotations.Test;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CardNotPresentException;
import javax.smartcardio.CommandAPDU;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class SynthesizedCardLifecycleTest {

    private static final CommandAPDU SELECT = new CommandAPDU(0x00, 0xA4, 0x04, 0x00);
    private static final CommandAPDU GET_DATA = new CommandAPDU(0x80, 0xCA, 0x9F, 0x7F, 256);

    // A transport that records its own teardown and then fails it: a session that cannot be closed
    // cleanly must not break disconnect() or yank().
    private static final class BadCloseBIBO implements BIBO {
        final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public byte[] transceive(byte[] bytes) {
            return HexUtils.hex2bin("9000");
        }

        @Override
        public void close() {
            closed.set(true);
            throw new BIBOException("close failed");
        }
    }

    private static String last(List<String> sent) {
        return sent.get(sent.size() - 1);
    }

    // One connected card, three channels: the basic one plus logical 1 and 5, each rewriting the
    // CLA of everything it carries per ISO 7816-4.
    @Test
    public void sessionRunsThroughLogicalChannels() throws CardException {
        var sent = new ArrayList<String>();
        var opens = new AtomicInteger();
        // Hands out channel 1 first, then channel 5 to reach the extended CLA encoding.
        BIBO card = command -> {
            sent.add(HexUtils.bin2hex(command));
            if ((command[1] & 0xFF) == 0x70 && (command[2] & 0xFF) == 0x00) {
                return HexUtils.hex2bin(opens.incrementAndGet() == 1 ? "019000" : "059000");
            }
            return HexUtils.hex2bin("9000");
        };

        var t = new SynthesizedCardTerminal("sim");
        t.presentFactory(protocol -> card, SynthesizedCardTerminal.defaultAtr());
        Card c = t.connect("*");
        assertEquals(c.getProtocol(), "T=1");
        assertTrue(c.toString().contains("T=1"), c.toString());

        CardChannel basic = c.getBasicChannel();
        assertEquals(basic.getChannelNumber(), 0);
        assertSame(basic.getCard(), c);
        basic.transmit(SELECT);
        assertEquals(last(sent), "00A40400");

        CardChannel one = c.openLogicalChannel();
        assertEquals(one.getChannelNumber(), 1);
        one.transmit(SELECT);
        assertEquals(last(sent), "01A40400");

        CardChannel five = c.openLogicalChannel();
        assertEquals(five.getChannelNumber(), 5);
        five.transmit(SELECT);
        assertEquals(last(sent), "41A40400");
        // A proprietary CLA carries its own channel encoding and is passed through untouched.
        five.transmit(GET_DATA);
        assertEquals(last(sent), "80CA9F7F00");

        // MANAGE CHANNEL CLOSE names the channel in P2 and in its own CLA.
        five.close();
        assertEquals(last(sent), "41708005");
        five.close();
        assertEquals(last(sent), "41708005", "a second close sends nothing");
        expectThrows(IllegalStateException.class, five::getChannelNumber);
        expectThrows(IllegalStateException.class, () -> five.transmit(SELECT));

        one.close();
        assertEquals(last(sent), "01708001");
        c.disconnect(true);
        assertTrue(t.isCardPresent(), "a factory card survives disconnect");
    }

    // A presented card feeds sessions one of two ways: a queue that empties and takes the card with
    // it, or a factory that mints a fresh transport per connect until the card is pulled.
    @Test
    public void queueAndFactoryModesFeedSessions() throws CardException {
        // Queue: a probe cycle consumes nothing, a reset disconnect consumes one session.
        var queued = new SynthesizedCardTerminal("sim");
        byte[] atr = HexUtils.hex2bin("3B90964F46");
        queued.present(List.of(MockBIBO.of("9000"), MockBIBO.of("6A82")), atr);

        Card probe = queued.connect("*");
        assertEquals(probe.getATR().getBytes(), atr);
        probe.disconnect(false);
        assertTrue(queued.isCardPresent(), "a probe cycle leaves the queue untouched");

        Card first = queued.connect("*");
        assertEquals(first.getBasicChannel().transmit(SELECT).getSW(), 0x9000);
        first.disconnect(true);
        assertTrue(queued.isCardPresent());
        Card second = queued.connect("*");
        assertEquals(second.getBasicChannel().transmit(SELECT).getSW(), 0x6A82);
        second.disconnect(true);
        assertFalse(queued.isCardPresent(), "the card leaves with the last queued session");

        // Factory: the protocol the terminal established reaches the factory, and only yank() ends it.
        var protocols = new ArrayList<String>();
        var contactless = new SynthesizedCardTerminal("sim", "T=CL");
        contactless.presentFactory(protocol -> {
            protocols.add(protocol);
            return MockBIBO.of("9000");
        }, SynthesizedCardTerminal.defaultAtr());
        for (var i = 0; i < 2; i++) {
            Card c = contactless.connect("T=CL");
            c.getBasicChannel().transmit(SELECT);
            c.disconnect(true);
            assertTrue(contactless.isCardPresent(), "a factory card survives disconnect");
        }
        assertEquals(protocols, List.of("T=CL", "T=CL"));
        contactless.yank();
        assertFalse(contactless.isCardPresent());
    }

    // A reader holds one card at a time and refuses to hand out what it does not have.
    @Test
    public void presentationContract() throws CardException {
        var t = new SynthesizedCardTerminal("sim");
        assertFalse(t.isCardPresent());
        expectThrows(CardNotPresentException.class, () -> t.connect("*"));
        expectThrows(IllegalArgumentException.class,
                () -> t.present(List.of(), SynthesizedCardTerminal.defaultAtr()));
        expectThrows(IllegalArgumentException.class, () -> t.waitForCardPresent(-1));
        expectThrows(IllegalArgumentException.class, () -> t.waitForCardAbsent(-1));

        // Every presentation flavour refuses an occupied reader.
        t.present(MockBIBO.of("9000"));
        expectThrows(IllegalStateException.class, () -> t.present(MockBIBO.of("9000")));
        expectThrows(IllegalStateException.class,
                () -> t.presentFactory(p -> MockBIBO.of("9000"), SynthesizedCardTerminal.defaultAtr()));
        expectThrows(IllegalStateException.class,
                () -> t.present(new CompletableFuture<BIBO>(), SynthesizedCardTerminal.defaultAtr()));
        expectThrows(IllegalStateException.class,
                () -> t.presentAsync(new CompletableFuture<List<BIBO>>(), SynthesizedCardTerminal.defaultAtr()));

        // A yank tears the live session down, even when the transport fails to close.
        var yanked = new BadCloseBIBO();
        var t2 = new SynthesizedCardTerminal("sim");
        t2.present(yanked);
        t2.connect("*").getBasicChannel().transmit(SELECT);
        t2.yank();
        assertTrue(yanked.closed.get());
        assertFalse(t2.isCardPresent());

        // So does a disconnect with reset.
        var reset = new BadCloseBIBO();
        var t3 = new SynthesizedCardTerminal("sim");
        t3.present(reset);
        Card c3 = t3.connect("*");
        c3.getBasicChannel().transmit(SELECT);
        c3.disconnect(true);
        assertTrue(reset.closed.get());
        assertFalse(t3.isCardPresent(), "the queue is depleted, so the card leaves with the session");
    }

    // An asynchronously presented card only counts once its transport materializes.
    @Test
    public void asyncPresentationSettlesOrClears() throws Exception {
        // A failed future releases the reservation and the reader takes a new card.
        var t = new SynthesizedCardTerminal("sim");
        var boom = new CompletableFuture<BIBO>();
        t.present(boom, SynthesizedCardTerminal.defaultAtr());
        assertFalse(t.isCardPresent());
        boom.completeExceptionally(new IllegalStateException("card fell out"));
        assertFalse(t.waitForCardPresent(50));
        t.present(MockBIBO.of("9000"));
        assertTrue(t.isCardPresent());

        // A completion that lands after a yank belongs to the departed card and is dropped.
        var t2 = new SynthesizedCardTerminal("sim");
        var late = new CompletableFuture<List<BIBO>>();
        t2.presentAsync(late, SynthesizedCardTerminal.defaultAtr());
        t2.yank();
        late.complete(List.of(MockBIBO.of("9000")));
        assertFalse(t2.waitForCardPresent(50));

        // So does a failure that lands after a yank.
        var t3 = new SynthesizedCardTerminal("sim");
        var lateBoom = new CompletableFuture<BIBO>();
        t3.present(lateBoom, SynthesizedCardTerminal.defaultAtr());
        t3.yank();
        t3.present(MockBIBO.of("9000"));
        lateBoom.completeExceptionally(new IllegalStateException("too late"));
        assertTrue(t3.isCardPresent(), "the stale failure must not evict the current card");

        // A completion in time feeds the queue as usual and the card leaves when it empties.
        var t4 = new SynthesizedCardTerminal("sim");
        var arriving = new CompletableFuture<List<BIBO>>();
        t4.presentAsync(arriving, SynthesizedCardTerminal.defaultAtr());
        assertFalse(t4.isCardPresent());
        arriving.complete(List.of(MockBIBO.of("9000")));
        assertTrue(t4.waitForCardPresent(50));
        Card c = t4.connect("*");
        c.getBasicChannel().transmit(SELECT);
        c.disconnect(true);
        assertFalse(t4.isCardPresent());
    }

    // connect(String) enforces the javax.smartcardio protocol contract.
    @Test
    public void connectValidatesProtocol() throws CardException {
        var t = new SynthesizedCardTerminal("sim");   // terminal establishes T=1
        t.presentFactory(p -> MockBIBO.of("9000"), SynthesizedCardTerminal.defaultAtr());
        expectThrows(IllegalArgumentException.class, () -> t.connect("T=bogus"));
        expectThrows(CardException.class, () -> t.connect("T=0"));
        Card card = t.connect("*");
        assertEquals(card.getProtocol(), "T=1");
        assertSame(t.connect("T=1"), card);
        assertSame(t.connect("*"), card);
    }

    // A handle must never transact once its card is gone.
    @Test
    public void handleRejectedOnceCardIsGone() throws CardException {
        // A handle taken before yank fails as CardException on the new card.
        var pulled = new SynthesizedCardTerminal("sim");
        pulled.present(MockBIBO.of("AA9000"));
        Card removed = pulled.connect("*");     // handle taken before any APDU
        pulled.yank();
        pulled.present(MockBIBO.of("BB9000"));   // a different card in its place
        expectThrows(CardException.class, () -> removed.getBasicChannel().transmit(SELECT));

        // A disconnected handle must not pop the next queued session.
        var released = new SynthesizedCardTerminal("sim");
        released.present(List.of(MockBIBO.of("AA9000"), MockBIBO.of("BB9000")),
                SynthesizedCardTerminal.defaultAtr());
        Card card = released.connect("*");
        CardChannel channel = card.getBasicChannel();
        channel.transmit(SELECT);                // first session returns AA9000
        // The basic channel cannot be closed while the card is live.
        expectThrows(IllegalStateException.class, () -> channel.close());
        card.disconnect(true);
        card.disconnect(true);                   // repeat disconnect is a no-op per contract
        expectThrows(IllegalStateException.class, () -> channel.transmit(SELECT));
        // A disconnected card hands out no basic channel either.
        expectThrows(IllegalStateException.class, card::getBasicChannel);

        // Two PC/SC contexts share one card: once the queue is depleted through the first handle,
        // the second finds nothing to talk to even though its card was never removed.
        var shared = new SynthesizedCardTerminal("sim");
        shared.present(MockBIBO.of("9000"));
        var other = new AtomicReference<Card>();
        var context = new Thread(() -> {
            try {
                other.set(shared.connect("*"));
            } catch (CardException e) {
                throw new IllegalStateException(e);
            }
        });
        context.start();
        try {
            context.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Card mine = shared.connect("*");
        mine.getBasicChannel().transmit(SELECT);
        mine.disconnect(true);
        var gone = expectThrows(CardException.class, () -> other.get().getBasicChannel().transmit(SELECT));
        assertTrue(gone.getMessage().contains("No BIBO available"), gone.getMessage());
    }

    // MANAGE CHANNEL belongs to the channel lifecycle API. CardBIBO relies on this guard: it
    // intercepts MANAGE CHANNEL itself and routes it to openLogicalChannel/close, so a raw one
    // reaching transmit means the interception was bypassed.
    @Test
    public void transmitRejectsRawManageChannel() throws CardException {
        var t = new SynthesizedCardTerminal("sim");
        t.presentFactory(p -> MockBIBO.of("9000"), SynthesizedCardTerminal.defaultAtr());
        CardChannel ch = t.connect("*").getBasicChannel();
        expectThrows(IllegalArgumentException.class,
                () -> ch.transmit(new CommandAPDU(0x00, 0x70, 0x00, 0x00)));
        // The rejection did not reach the card: the session still answers a real exchange.
        assertEquals(ch.transmit(SELECT).getSW(), 0x9000);
    }

    // waitForCardPresent honours its timeout and interrupt.
    @Test
    public void waitForCardPresentSemantics() throws Exception {
        var t = new SynthesizedCardTerminal("sim");

        // A 1ms timeout with no card must spend about a millisecond before reporting absence.
        long start = System.nanoTime();
        boolean present = t.waitForCardPresent(1);
        long elapsed = System.nanoTime() - start;
        assertFalse(present);
        assertTrue(elapsed >= 900_000, "waited only " + elapsed + "ns");

        // A blocking wait interrupted mid-flight throws CardException.
        var thrown = new AtomicReference<Throwable>();
        var waiter = new Thread(() -> {
            try {
                t.waitForCardPresent(0);
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        waiter.start();
        Thread.sleep(50);
        waiter.interrupt();
        waiter.join();
        assertTrue(thrown.get() instanceof CardException, "expected CardException but got " + thrown.get());
    }

    // Anything the card cannot answer properly surfaces as CardException, never as a raw BIBOException.
    @Test
    public void sessionFailuresSurfaceAsCardException() throws CardException {
        // A transport that refuses to come up.
        var dead = new SynthesizedCardTerminal("sim");
        dead.presentFactory(p -> {
            throw new BIBOException("no power");
        }, SynthesizedCardTerminal.defaultAtr());
        var noSession = expectThrows(CardException.class,
                () -> dead.connect("*").getBasicChannel().transmit(SELECT));
        assertTrue(noSession.getMessage().contains("Failed to create session"), noSession.getMessage());

        // A sub-2-byte response is not an APDU.
        var runt = new SynthesizedCardTerminal("sim");
        runt.present(MockBIBO.of("90"));
        expectThrows(CardException.class, () -> runt.connect("*").getBasicChannel().transmit(SELECT));

        // MANAGE CHANNEL OPEN: refused, answered without a channel id, out of the 1..19 range,
        // or not answered at all.
        assertTrue(openFails("6A81").getMessage().contains("MANAGE CHANNEL failed"));
        assertTrue(openFails("9000").getMessage().contains("no channel id"));
        assertTrue(openFails("149000").getMessage().contains("invalid channel id"));
        assertTrue(openFails().getMessage().contains("depleted"));

        // MANAGE CHANNEL CLOSE that the card refuses, and one it never answers at all: both retire
        // the channel regardless.
        assertTrue(closeFails("019000", "6A86").getMessage().contains("MANAGE CHANNEL CLOSE failed"));
        var stuck = new SynthesizedCardTerminal("sim");
        stuck.present(MockBIBO.of("019000"));
        CardChannel ch = stuck.connect("*").openLogicalChannel();
        expectThrows(CardException.class, ch::close);
        expectThrows(IllegalStateException.class, () -> ch.transmit(SELECT));
    }

    private static CardException openFails(String... responses) throws CardException {
        var t = new SynthesizedCardTerminal("sim");
        t.present(MockBIBO.of(responses));
        Card card = t.connect("*");
        return expectThrows(CardException.class, card::openLogicalChannel);
    }

    private static CardException closeFails(String... responses) throws CardException {
        var t = new SynthesizedCardTerminal("sim");
        t.present(MockBIBO.of(responses));
        CardChannel ch = t.connect("*").openLogicalChannel();
        return expectThrows(CardException.class, ch::close);
    }
}
