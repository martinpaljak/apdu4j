// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc.sim;

import apdu4j.core.BIBO;
import apdu4j.core.HexUtils;
import apdu4j.core.MockBIBO;
import org.testng.annotations.Test;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class SynthesizedCardLifecycleTest {

    // A handle must never transact once its card is gone.
    @Test
    public void handleRejectedOnceCardIsGone() throws CardException {
        var cmd = new CommandAPDU(0x00, 0xA4, 0x04, 0x00);

        // A handle taken before yank fails as CardException on the new card.
        var pulled = new SynthesizedCardTerminal("sim");
        pulled.present(MockBIBO.of("AA9000"));
        Card removed = pulled.connect("*");     // handle taken before any APDU
        pulled.yank();
        pulled.present(MockBIBO.of("BB9000"));   // a different card in its place
        expectThrows(CardException.class, () -> removed.getBasicChannel().transmit(cmd));

        // A disconnected handle must not pop the next queued session.
        var released = new SynthesizedCardTerminal("sim");
        released.present(List.of(MockBIBO.of("AA9000"), MockBIBO.of("BB9000")),
                SynthesizedCardTerminal.defaultAtr());
        Card card = released.connect("*");
        CardChannel channel = card.getBasicChannel();
        channel.transmit(cmd);                   // first session returns AA9000
        // The basic channel cannot be closed while the card is live.
        expectThrows(IllegalStateException.class, () -> channel.close());
        card.disconnect(true);
        expectThrows(IllegalStateException.class, () -> channel.transmit(cmd));
        // A disconnected card hands out no basic channel either.
        expectThrows(IllegalStateException.class, card::getBasicChannel);
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

    // Exclusive access is single-holder and released explicitly.
    @Test
    public void exclusiveAccess() throws CardException {
        var t = new SynthesizedCardTerminal("sim");
        t.present(MockBIBO.of("9000"));
        Card card = t.connect("*");
        card.beginExclusive();
        expectThrows(CardException.class, card::beginExclusive);       // already held
        card.endExclusive();
        expectThrows(IllegalStateException.class, card::endExclusive); // no matching begin
        card.beginExclusive();
        card.disconnect(true);
        expectThrows(IllegalStateException.class, card::endExclusive); // disposed handle
    }

    // A channel encodes its number into every APDU's CLA (ISO 7816-4).
    @Test
    public void logicalChannelEncodesCLA() throws CardException {
        var sent = new ArrayList<byte[]>();
        BIBO bibo = command -> {
            sent.add(command.clone());
            if (command.length >= 2 && command[1] == 0x70) {
                return new byte[]{0x01, (byte) 0x90, 0x00};
            }
            return new byte[]{(byte) 0x90, 0x00};
        };
        var t = new SynthesizedCardTerminal("sim");
        t.present(bibo);
        Card card = t.connect("*");
        CardChannel ch = card.openLogicalChannel();
        ch.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00));
        assertEquals(HexUtils.bin2hex(sent.get(sent.size() - 1)), "01A40400");

        // A closed channel rejects any further transmit.
        ch.close();
        assertEquals(HexUtils.bin2hex(sent.get(sent.size() - 1)), "01708001");
        expectThrows(IllegalStateException.class,
                () -> ch.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00)));
        // A closed channel has no number to report.
        expectThrows(IllegalStateException.class, ch::getChannelNumber);

        // A non-9000 close response is a card error.
        var t2 = new SynthesizedCardTerminal("sim");
        t2.present(command -> {
            if (command.length >= 3 && command[1] == 0x70 && (command[2] & 0xFF) == 0x80) {
                return new byte[]{0x6A, (byte) 0x86};
            }
            return new byte[]{0x01, (byte) 0x90, 0x00};
        });
        CardChannel ch2 = t2.connect("*").openLogicalChannel();
        expectThrows(CardException.class, () -> ch2.close());
    }

    // transmit refuses a raw MANAGE CHANNEL APDU.
    @Test
    public void transmitRejectsInvalidInput() throws CardException {
        var t = new SynthesizedCardTerminal("sim");
        t.present(MockBIBO.of("9000"));
        Card card = t.connect("*");
        CardChannel ch = card.getBasicChannel();
        expectThrows(IllegalArgumentException.class,
                () -> ch.transmit(new CommandAPDU(0x00, 0x70, 0x00, 0x00)));
        // A response buffer that cannot hold a full APDU is rejected before anything is sent.
        expectThrows(IllegalArgumentException.class,
                () -> ch.transmit(ByteBuffer.wrap(new byte[]{0x00, (byte) 0xA4, 0x04, 0x00}),
                        ByteBuffer.allocate(10)));
    }

    // An empty BIBO list is rejected to avoid wedging the reader.
    @Test
    public void presentRejectsEmptyList() {
        var t = new SynthesizedCardTerminal("sim");
        expectThrows(IllegalArgumentException.class,
                () -> t.present(List.of(), SynthesizedCardTerminal.defaultAtr()));
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

    // Malformed card responses surface as CardException.
    @Test
    public void malformedResponsesSurfaceAsCardException() throws CardException {
        // A 9000 open with no channel-id byte is a CardException.
        var t1 = new SynthesizedCardTerminal("sim");
        t1.present(MockBIBO.of("9000"));
        Card c1 = t1.connect("*");
        expectThrows(CardException.class, () -> c1.openLogicalChannel());

        // A sub-2-byte response is a CardException.
        var t2 = new SynthesizedCardTerminal("sim");
        t2.present(MockBIBO.of("90"));
        Card c2 = t2.connect("*");
        var cmd = new CommandAPDU(0x00, 0xA4, 0x04, 0x00);
        expectThrows(CardException.class, () -> c2.getBasicChannel().transmit(cmd));

        // An out-of-range channel id (0x14) is a CardException.
        var t3 = new SynthesizedCardTerminal("sim");
        t3.present(MockBIBO.of("149000"));
        expectThrows(CardException.class, () -> t3.connect("*").openLogicalChannel());
    }
}
