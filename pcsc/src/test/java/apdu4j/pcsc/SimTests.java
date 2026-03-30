/*
 * Copyright (c) 2026-present Martin Paljak <martin@martinpaljak.net>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package apdu4j.pcsc;

import apdu4j.core.*;
import apdu4j.pcsc.sim.SynthesizedCardTerminal;
import apdu4j.pcsc.sim.SynthesizedCardTerminals;
import apdu4j.prefs.Preferences;
import org.testng.Assert;
import org.testng.annotations.Test;

import javax.smartcardio.CardTerminals;
import javax.smartcardio.CommandAPDU;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class SimTests {

    // === Replay: end-to-end through javax.smartcardio SPI ===

    @Test
    void testReplaySession() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        terminals.addTerminal(SynthesizedCardTerminal.replay(SimTests.class.getResourceAsStream("test.dump")));

        var factory = terminals.toFactory();
        var list = factory.terminals().list();
        Assert.assertEquals(list.size(), 1);

        var terminal = list.get(0);
        Assert.assertEquals(terminal.getName(), "APDUReplay terminal 0");
        Assert.assertTrue(terminal.isCardPresent());

        var card = terminal.connect("*");
        Assert.assertEquals(card.getProtocol(), "T=1");
        Assert.assertNotNull(card.getATR());
        Assert.assertTrue(card.getATR().getBytes().length > 2);

        var channel = card.getBasicChannel();
        var r = channel.transmit(new CommandAPDU(HexUtils.hex2bin("00A4040000")));
        Assert.assertEquals(r.getSW(), 0x9000);

        card.disconnect(true);
        Assert.assertFalse(terminal.isCardPresent());
    }

    @Test
    void testReplayManager() {
        try (var mgr = TerminalManager.replayManager(SimTests.class.getResourceAsStream("test.dump"))) {
            var result = Readers.select(mgr).run(bibo ->
                    bibo.transceive(HexUtils.hex2bin("00A4040000"))
            );
            Assert.assertNotNull(result);
            Assert.assertTrue(result.length > 2);
        }
    }

    // === Card lifecycle: single, queue, and factory modes ===

    @Test
    void testSingleBiboLifecycle() throws Exception {
        var terminal = new SynthesizedCardTerminal("Test Reader");
        Assert.assertFalse(terminal.isCardPresent());

        // Present, transmit, disconnect(true) - auto-yanks single BIBO
        terminal.present(MockBIBO.of("9000"));
        Assert.assertTrue(terminal.isCardPresent());
        var card = terminal.connect("*");
        Assert.assertEquals(card.getATR().getBytes(), SynthesizedCardTerminal.defaultAtr());
        card.getBasicChannel().transmit(new CommandAPDU(HexUtils.hex2bin("00A4040000")));
        card.disconnect(true);
        Assert.assertFalse(terminal.isCardPresent());

        // Re-present with custom ATR
        byte[] customAtr = HexUtils.hex2bin("3B90964F46");
        terminal.present(MockBIBO.of("9000"), customAtr);
        Assert.assertEquals(terminal.connect("*").getATR().getBytes(), customAtr);
    }

    @Test
    void testQueueAndFactoryModes() throws Exception {
        // Queue mode: each disconnect(true) pops next BIBO, card gone when depleted
        var queueTerminal = new SynthesizedCardTerminal("Queue Reader");
        queueTerminal.present(List.of(MockBIBO.of("9000"), MockBIBO.of("6A82")),
                SynthesizedCardTerminal.defaultAtr());

        var c1 = queueTerminal.connect("*");
        c1.getBasicChannel().transmit(new CommandAPDU(HexUtils.hex2bin("00A4040000")));
        c1.disconnect(true);
        Assert.assertTrue(queueTerminal.isCardPresent(), "Queue still has one more");

        var c2 = queueTerminal.connect("*");
        Assert.assertEquals(c2.getBasicChannel().transmit(
                new CommandAPDU(HexUtils.hex2bin("00A4040000"))).getSW(), 0x6A82);
        c2.disconnect(true);
        Assert.assertFalse(queueTerminal.isCardPresent(), "Queue depleted");

        // Factory mode: card persists, each connect gets fresh BIBO from factory
        var callCount = new AtomicInteger(0);
        var factoryTerminal = new SynthesizedCardTerminal("Factory Reader");
        factoryTerminal.present(protocol -> {
            callCount.incrementAndGet();
            return MockBIBO.of("9000");
        }, SynthesizedCardTerminal.defaultAtr());

        var fc1 = factoryTerminal.connect("*");
        fc1.getBasicChannel().transmit(new CommandAPDU(HexUtils.hex2bin("00A4040000")));
        fc1.disconnect(true);
        Assert.assertTrue(factoryTerminal.isCardPresent(), "Factory survives disconnect");
        var fc2 = factoryTerminal.connect("*");
        fc2.getBasicChannel().transmit(new CommandAPDU(HexUtils.hex2bin("00A4040000")));
        fc2.disconnect(true);
        Assert.assertEquals(callCount.get(), 2, "Factory called each connect");

        factoryTerminal.yank();
        Assert.assertFalse(factoryTerminal.isCardPresent(), "Only yank() removes factory card");
    }

    // === javax.smartcardio API: channels and ByteBuffer ===

    @Test
    void testLogicalChannelAndByteBuffer() throws Exception {
        var terminal = new SynthesizedCardTerminal("Channel Reader");
        terminal.present(MockBIBO.of("019000", "AABB9000", "9000"));

        var card = terminal.connect("*");
        Assert.assertEquals(card.getBasicChannel().getChannelNumber(), 0);
        Assert.assertSame(card.getBasicChannel().getCard(), card);

        var logical = card.openLogicalChannel();
        Assert.assertEquals(logical.getChannelNumber(), 1);

        // ByteBuffer transmit
        var cmd = ByteBuffer.wrap(HexUtils.hex2bin("00A4040000"));
        var resp = ByteBuffer.allocate(256);
        var len = card.getBasicChannel().transmit(cmd, resp);
        Assert.assertTrue(len > 0);
        resp.flip();
        Assert.assertEquals(resp.remaining(), len);

        logical.close();
        card.disconnect(true);
    }

    // === Async card insertion ===

    @Test
    void testDelayedCardInsertion() {
        var terminal = new SynthesizedCardTerminal("Contactless Reader");
        var biboCf = new CompletableFuture<BIBO>();
        terminal.present(biboCf, SynthesizedCardTerminal.defaultAtr());
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            biboCf.complete(MockBIBO.of("9000"));
        });
        try (var mgr = TerminalManager.managerOf(terminal)) {
            var start = System.currentTimeMillis();
            var bibo = Readers.select(mgr).connectWhenReady();
            var elapsed = System.currentTimeMillis() - start;
            Assert.assertNotNull(bibo);
            Assert.assertTrue(elapsed >= 150, "Should have waited for card: " + elapsed + "ms");
            bibo.close();
        }
    }

    @Test
    void testAsyncMultiBibo() throws Exception {
        var terminal = new SynthesizedCardTerminal("Async Multi Reader");
        var biboCf = new CompletableFuture<List<BIBO>>();
        terminal.presentAsync(biboCf, SynthesizedCardTerminal.defaultAtr());
        Assert.assertFalse(terminal.isCardPresent());

        biboCf.complete(List.of(MockBIBO.of("9000"), MockBIBO.of("6A82")));
        Assert.assertTrue(terminal.isCardPresent());

        // Deplete queue
        var ac1 = terminal.connect("*");
        ac1.getBasicChannel().transmit(new CommandAPDU(HexUtils.hex2bin("00A4040000")));
        ac1.disconnect(true);
        Assert.assertTrue(terminal.isCardPresent());
        var ac2 = terminal.connect("*");
        ac2.getBasicChannel().transmit(new CommandAPDU(HexUtils.hex2bin("00A4040000")));
        ac2.disconnect(true);
        Assert.assertFalse(terminal.isCardPresent());
    }

    // === SynthesizedCardTerminals state filtering ===

    @Test
    void testListStateFiltering() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        var present = new SynthesizedCardTerminal("Present Reader");
        present.present(MockBIBO.of("9000"));
        var absent = new SynthesizedCardTerminal("Empty Reader");
        terminals.addTerminal(present);
        terminals.addTerminal(absent);

        Assert.assertEquals(terminals.list(CardTerminals.State.ALL).size(), 2);
        Assert.assertEquals(terminals.list(CardTerminals.State.CARD_PRESENT).size(), 1);
        Assert.assertEquals(terminals.list(CardTerminals.State.CARD_INSERTION).size(), 1);
        Assert.assertEquals(terminals.list(CardTerminals.State.CARD_ABSENT).size(), 1);
        Assert.assertEquals(terminals.list(CardTerminals.State.CARD_REMOVAL).size(), 1);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    void testDuplicateTerminalNameRejected() {
        var terminals = new SynthesizedCardTerminals();
        terminals.addTerminal(new SynthesizedCardTerminal("Reader A"));
        terminals.addTerminal(new SynthesizedCardTerminal("Reader A"));
    }

    // === Protocol variants ===

    @Test
    void testProtocols() throws Exception {
        // T=0 via replay
        try (var mgr = TerminalManager.replayManager(SimTests.class.getResourceAsStream("t0.dump"))) {
            Assert.assertEquals(
                    Readers.select(mgr).protocol("T=0").run(b -> b.transceive(HexUtils.hex2bin("00A4040000"))),
                    HexUtils.hex2bin("9000"));
        }

        // T=CL via factory - protocol string passes through to synthesized terminal
        var receivedProtocol = new AtomicReference<String>();
        var terminal = new SynthesizedCardTerminal("NFC Reader", "T=CL");
        terminal.present(protocol -> {
            receivedProtocol.set(protocol);
            return MockBIBO.of("9000");
        }, SynthesizedCardTerminal.defaultAtr());

        Assert.assertEquals(terminal.connect("T=CL").getProtocol(), "T=CL");
        try (var mgr = TerminalManager.managerOf(terminal)) {
            Readers.select(mgr).protocol("T=CL").run(b -> b.transceive(HexUtils.hex2bin("00A4040000")));
        }
        Assert.assertEquals(receivedProtocol.get(), "T=CL");
    }

    // === Probe cycle and negative timeouts ===

    @Test
    void testProbeCycleDoesNotConsumeBibo() throws Exception {
        var terminal = new SynthesizedCardTerminal("Probe Reader");
        terminal.present(MockBIBO.of("9000"));

        // Probe: connect, getATR, disconnect(false) - should not consume BIBO
        var probeCard = terminal.connect("*");
        probeCard.getATR();
        probeCard.disconnect(false);

        Assert.assertTrue(terminal.isCardPresent());
        Assert.assertEquals(terminal.connect("*").getBasicChannel().transmit(
                new CommandAPDU(HexUtils.hex2bin("00A4040000"))).getSW(), 0x9000);
    }

    @Test
    void testNegativeTimeoutsRejected() throws Exception {
        var terminal = new SynthesizedCardTerminal("Timeout Reader");
        Assert.assertThrows(IllegalArgumentException.class, () -> terminal.waitForCardPresent(-1));
        Assert.assertThrows(IllegalArgumentException.class, () -> terminal.waitForCardAbsent(-1));
    }

    // === Fluent API: wrappers, preferences, DWIM ===

    @Test
    void testFluentApiWithLoggingAndDump() {
        var log = new ByteArrayOutputStream();
        var dump = new ByteArrayOutputStream();
        try (var mgr = TerminalManager.replayManager(SimTests.class.getResourceAsStream("test.dump"))) {
            Readers.select(mgr).log(log).dump(dump).protocol("T=1").run(bibo ->
                    bibo.transceive(HexUtils.hex2bin("00A4040000"))
            );
        }
        var logStr = log.toString();
        Assert.assertTrue(logStr.contains("A>> (4+0) 00A40400 00"), "Command APDU format");
        Assert.assertTrue(logStr.contains("A<< (102+2) "), "Response APDU length format");
        Assert.assertTrue(logStr.contains(" 9000 ("), "Response SW and timing");
        Assert.assertTrue(dump.toString().contains("# ATR:"), "Dump should contain ATR header");
    }

    @Test
    void testPreferencesBasedConfig() {
        var terminal = new SynthesizedCardTerminal("Prefs Reader", "T=CL");
        terminal.present(protocol -> {
            Assert.assertEquals(protocol, "T=CL");
            return MockBIBO.of("9000");
        }, SynthesizedCardTerminal.defaultAtr());

        var prefs = new Preferences()
                .with(Readers.PROTOCOL, "T=CL")
                .with(Readers.FRESH_TAP, false);

        try (var mgr = TerminalManager.managerOf(terminal)) {
            Assert.assertEquals(
                    Readers.select(mgr).with(prefs).run(b -> b.transceive(HexUtils.hex2bin("00A4040000"))),
                    HexUtils.hex2bin("9000"));
        }
    }

    @Test
    void testMultiReaderDWIM() {
        var reader1 = new SynthesizedCardTerminal("ACS ACR122U 0");
        reader1.present(MockBIBO.of("9000"));
        var reader2 = new SynthesizedCardTerminal("Gemalto USB SmartCard Reader 0");
        reader2.present(MockBIBO.of("6A82"));
        try (var mgr = TerminalManager.managerOf(reader1, reader2)) {
            // Hint selects ACR
            Assert.assertEquals(Readers.select(mgr, "ACR").run(b -> b.transceive(HexUtils.hex2bin("00A4040000"))),
                    HexUtils.hex2bin("9000"));

            // Ignore ACR picks Gemalto
            Assert.assertEquals(Readers.select(mgr).ignore("ACR").run(b -> b.transceive(HexUtils.hex2bin("00A4040000"))),
                    HexUtils.hex2bin("6A82"));

            // No match throws with available list
            try {
                Readers.select(mgr, "NonExistent").run(b -> b.transceive(HexUtils.hex2bin("00A40400")));
                Assert.fail("Should have thrown");
            } catch (NoMatchingReaderException e) {
                Assert.assertEquals(e.getAvailable().size(), 2);
                Assert.assertTrue(e.getAvailable().contains("ACS ACR122U 0"));
            }
        }
    }

    // === CardBIBO channel interception and APDUBIBO ===

    @Test
    void testTypedAndRawChannelAPIs() {
        var terminal = new SynthesizedCardTerminal("Channel Reader");
        terminal.present(MockBIBO.of("9000", "6A82", "019000", "9000"));
        try (var mgr = TerminalManager.managerOf(terminal)) {
            Readers.select(mgr).run(bibo -> {
                // APDUBIBO typed wrapper
                var apdu = new APDUBIBO(bibo);
                Assert.assertEquals(apdu.transmit(new apdu4j.core.CommandAPDU(0x00, 0xA4, 0x04, 0x00)).getSW(), 0x9000);
                Assert.assertEquals(apdu.transmit(new apdu4j.core.CommandAPDU(0x00, 0xA4, 0x04, 0x00)).getSW(), 0x6A82);

                // Raw BIBO channel interception (OPEN CHANNEL + CLOSE CHANNEL)
                Assert.assertEquals(bibo.transceive(HexUtils.hex2bin("00700000")), HexUtils.hex2bin("019000"));
                Assert.assertEquals(bibo.transceive(HexUtils.hex2bin("01708000")), HexUtils.hex2bin("9000"));
                return null;
            });
        }
    }

    // === Dump round-trip ===

    @Test
    void testDumpRoundTrip() {
        var dump = new ByteArrayOutputStream();
        try (var mgr = TerminalManager.replayManager(SimTests.class.getResourceAsStream("test.dump"))) {
            Readers.select(mgr).dump(dump).run(b -> b.transceive(HexUtils.hex2bin("00A4040000")));
        }
        try (var mgr = TerminalManager.replayManager(new ByteArrayInputStream(dump.toByteArray()))) {
            var result = Readers.select(mgr).run(b -> b.transceive(HexUtils.hex2bin("00A4040000")));
            Assert.assertNotNull(result);
            Assert.assertTrue(result.length >= 2);
        }
    }

    // === Error propagation ===

    @Test
    void testErrorPropagation() {
        var terminal = new SynthesizedCardTerminal("Error Reader");
        terminal.present(MockBIBO.of());
        var dump = new ByteArrayOutputStream();
        try (var mgr = TerminalManager.managerOf(terminal)) {
            try {
                Readers.select(mgr).dump(dump).run(b -> b.transceive(HexUtils.hex2bin("00A4040000")));
                Assert.fail("Should have thrown");
            } catch (BIBOException e) {
                Assert.assertNotNull(e.getCause());
            }
        }
        var dumpStr = dump.toString();
        Assert.assertTrue(dumpStr.contains("# ATR:"), "Dump header written before transceive");
        Assert.assertTrue(dumpStr.contains("00A4040000"), "Failed command logged before error");
    }
}
