// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import apdu4j.core.*;
import apdu4j.pcsc.sim.SynthesizedCardTerminal;
import apdu4j.pcsc.sim.SynthesizedCardTerminals;
import apdu4j.prefs.Preference;
import apdu4j.prefs.PreferenceProvider;
import apdu4j.prefs.Preferences;
import org.testng.Assert;
import org.testng.annotations.Test;

import javax.smartcardio.CommandAPDU;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
        terminal.presentFactory(protocol -> {
            receivedProtocol.set(protocol);
            return MockBIBO.of("9000");
        }, SynthesizedCardTerminal.defaultAtr());

        Assert.assertEquals(terminal.connect("T=CL").getProtocol(), "T=CL");
        try (var mgr = TerminalManager.managerOf(terminal)) {
            Readers.select(mgr).protocol("T=CL").run(b -> b.transceive(HexUtils.hex2bin("00A4040000")));
        }
        Assert.assertEquals(receivedProtocol.get(), "T=CL");
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
        Assert.assertTrue(logStr.contains("A>> (4+0000) 00A40400 00"), "Command APDU format");
        Assert.assertTrue(logStr.contains("A<< (0102+2)"), "Response APDU length format");
        Assert.assertTrue(logStr.contains("ms)"), "Response timing present");
        Assert.assertTrue(logStr.contains("9000"), "Response SW present");
        Assert.assertTrue(dump.toString().contains("# ATR:"), "Dump should contain ATR header");
    }

    @Test
    void testPreferencesBasedConfig() {
        var terminal = new SynthesizedCardTerminal("Prefs Reader", "T=CL");
        terminal.presentFactory(protocol -> {
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

    @Test
    void testFromPreferences() {
        // Consumer-defined keys in their own namespace - library is namespace-agnostic
        var HINT = Preference.of("myapp.reader", String.class, "", false);
        var IGNORE = Preference.of("myapp.reader.ignore", String.class, "", false);

        var acr = new SynthesizedCardTerminal("ACS ACR122U 0");
        acr.present(MockBIBO.of("9000"));
        var gemalto = new SynthesizedCardTerminal("Gemalto USB SmartCard Reader 0");
        gemalto.present(MockBIBO.of("6A82"));

        try (var mgr = TerminalManager.managerOf(acr, gemalto)) {
            // Hint via Preferences picks the matching reader; empty IGNORE exercises blank branch in parseIgnoreHints
            var hintOnly = Preferences.of(HINT, "ACR");
            Assert.assertEquals(
                    Readers.fromPreferences(mgr, hintOnly, HINT, IGNORE).run(b -> b.transceive(HexUtils.hex2bin("00A4040000"))),
                    HexUtils.hex2bin("9000"));

            // Provider-backed prefs with semicolon-separated ignore (exercises parseIgnoreHints split + too-short filter)
            // "ab" is < 3 chars (filtered with warning); "ACR" survives and ignores the ACR reader
            var providerBacked = Preferences.from(PreferenceProvider.map(
                    Map.of("myapp.reader.ignore", "ab;ACR"), "test"));
            Assert.assertEquals(
                    Readers.fromPreferences(mgr, providerBacked, HINT, IGNORE).run(b -> b.transceive(HexUtils.hex2bin("00A4040000"))),
                    HexUtils.hex2bin("6A82"));
        }
    }

    // === CardBIBO channel interception and typed transmit ===

    @Test
    void testTypedAndRawChannelAPIs() {
        var terminal = new SynthesizedCardTerminal("Channel Reader");
        terminal.present(MockBIBO.of("9000", "6A82", "019000", "9000"));
        try (var mgr = TerminalManager.managerOf(terminal)) {
            Readers.select(mgr).run(bibo -> {
                // Typed transmit (default method on BIBO)
                Assert.assertEquals(bibo.transmit(new apdu4j.core.CommandAPDU(0x00, 0xA4, 0x04, 0x00)).getSW(), 0x9000);
                Assert.assertEquals(bibo.transmit(new apdu4j.core.CommandAPDU(0x00, 0xA4, 0x04, 0x00)).getSW(), 0x6A82);

                // CLOSE names the channel in P2 (01708001 closes channel 1).
                Assert.assertEquals(bibo.transceive(HexUtils.hex2bin("00700000")), HexUtils.hex2bin("019000"));
                Assert.assertEquals(bibo.transceive(HexUtils.hex2bin("01708001")), HexUtils.hex2bin("9000"));
                return null;
            });
        }
    }

    // === Disconnect disposition: what the session leaves behind in the reader ===

    // LEAVE keeps the card powered so the next session continues where this one stopped; RESET tears
    // the session down. UNPOWER is not covered here: javax.smartcardio narrows disconnect to a
    // boolean, so on any backend that is not jnasmartcardio it collapses into RESET.
    @Test
    void testDisconnectDispositions() {
        var left = new SynthesizedCardTerminal("Leave Reader");
        left.present(List.of(MockBIBO.of("9000", "6A82")), SynthesizedCardTerminal.defaultAtr());
        try (var mgr = TerminalManager.managerOf(left)) {
            var selector = Readers.select(mgr).disconnect(SCard.Disconnect.LEAVE);
            Assert.assertEquals(selector.run(b -> b.transceive(HexUtils.hex2bin("00A4040000"))),
                    HexUtils.hex2bin("9000"));
            Assert.assertTrue(left.isCardPresent(), "LEAVE keeps the card in the reader");
            Assert.assertEquals(selector.run(b -> b.transceive(HexUtils.hex2bin("00A4040000"))),
                    HexUtils.hex2bin("6A82"), "the same session answers the second run");

            // A BIBO that escapes its run() is already closed and says so
            var escaped = selector.run(b -> b);
            Assert.assertThrows(IllegalStateException.class,
                    () -> escaped.transceive(HexUtils.hex2bin("00A4040000")));
        }

        // A logging wrapper must not narrow the disposition on its way down: LoggingCard implements
        // PCSCCard so the full disposition reaches the backend instead of becoming a boolean.
        var logged = new SynthesizedCardTerminal("Logged Reader");
        logged.present(List.of(MockBIBO.of("9000", "6A82")), SynthesizedCardTerminal.defaultAtr());
        try (var mgr = TerminalManager.managerOf(logged)) {
            var selector = Readers.select(mgr).log(new ByteArrayOutputStream())
                    .disconnect(SCard.Disconnect.LEAVE);
            selector.run(b -> b.transceive(HexUtils.hex2bin("00A4040000")));
            Assert.assertTrue(logged.isCardPresent(), "LEAVE survives the logging wrapper");
            Assert.assertEquals(selector.run(b -> b.transceive(HexUtils.hex2bin("00A4040000"))),
                    HexUtils.hex2bin("6A82"), "the same session answers through the wrapper");
        }

        // RESET ends the session, so the reader serves the next queued card and then goes empty.
        var reset = new SynthesizedCardTerminal("Reset Reader");
        reset.present(List.of(MockBIBO.of("9000"), MockBIBO.of("6A82")), SynthesizedCardTerminal.defaultAtr());
        try (var mgr = TerminalManager.managerOf(reset)) {
            var selector = Readers.select(mgr).reset(true);
            Assert.assertEquals(selector.run(b -> b.transceive(HexUtils.hex2bin("00A4040000"))),
                    HexUtils.hex2bin("9000"));
            Assert.assertTrue(reset.isCardPresent(), "one card still queued");
            Assert.assertEquals(selector.run(b -> b.transceive(HexUtils.hex2bin("00A4040000"))),
                    HexUtils.hex2bin("6A82"), "a fresh session, not the previous one");
            Assert.assertFalse(reset.isCardPresent(), "the queue is depleted");
        }
    }

    // === Logical channel routing: CardBIBO flattens the channel API into one byte stream ===

    @Test
    void testLogicalChannelRouting() {
        // A card that assigns channel 5, reaching the ISO 7816-4 further-interindustry CLA range
        var terminal = new SynthesizedCardTerminal("Routing Reader");
        terminal.presentFactory(p -> command -> {
            if ((command[1] & 0xFF) == 0x70 && (command[2] & 0xFF) == 0x00) {
                return HexUtils.hex2bin("059000");
            }
            return HexUtils.hex2bin("9000");
        }, SynthesizedCardTerminal.defaultAtr());

        try (var mgr = TerminalManager.managerOf(terminal)) {
            Readers.select(mgr).run(bibo -> {
                // MANAGE CHANNEL OPEN is intercepted and answered with the assigned channel
                Assert.assertEquals(bibo.transceive(HexUtils.hex2bin("00700000")), HexUtils.hex2bin("059000"));
                // CLA 41 routes back to channel 5
                Assert.assertEquals(bibo.transceive(HexUtils.hex2bin("41A4040000")), HexUtils.hex2bin("9000"));
                // A proprietary CLA always rides the basic channel
                Assert.assertEquals(bibo.transceive(HexUtils.hex2bin("80CA9F7F00")), HexUtils.hex2bin("9000"));

                // Channels that were never opened are refused, whether addressed or closed
                Assert.assertThrows(BIBOException.class, () -> bibo.transceive(HexUtils.hex2bin("02A4040000")));
                Assert.assertThrows(BIBOException.class, () -> bibo.transceive(HexUtils.hex2bin("01708001")));

                // CLOSE CHANNEL is intercepted too, and retires the routing entry
                Assert.assertEquals(bibo.transceive(HexUtils.hex2bin("41708005")), HexUtils.hex2bin("9000"));
                Assert.assertThrows(BIBOException.class, () -> bibo.transceive(HexUtils.hex2bin("41A4040000")));
                return null;
            });
        }
    }

    // === Escape hatches: raw javax.smartcardio for code that needs it ===

    @Test
    void testRawTerminalAndCard() throws Exception {
        var terminal = new SynthesizedCardTerminal("Escape Reader");
        terminal.presentFactory(p -> MockBIBO.of("9000"), SynthesizedCardTerminal.defaultAtr());
        try (var mgr = TerminalManager.managerOf(terminal)) {
            var selector = Readers.select(mgr);
            Assert.assertEquals(selector.terminal().getName(), "Escape Reader");

            var card = selector.card();
            Assert.assertEquals(card.getProtocol(), "T=1");
            Assert.assertEquals(card.getBasicChannel().transmit(
                    new CommandAPDU(HexUtils.hex2bin("00A4040000"))).getSW(), 0x9000);
            card.disconnect(true);
        }

        // An empty reader fails the escape hatch outright rather than handing back nothing
        terminal.yank();
        try (var mgr = TerminalManager.managerOf(terminal)) {
            Assert.assertThrows(BIBOException.class, () -> Readers.select(mgr).card());
        }
    }

    // === Pass conflicts: two named passes coexist, an ambiguous one is refused ===

    @Test
    void testNamedPassesDoNotOverlap() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        terminals.addTerminal(new SynthesizedCardTerminal("Alpha Reader"));
        terminals.addTerminal(new SynthesizedCardTerminal("Beta Reader"));

        try (var mgr = new TerminalManager(terminals.toFactory())) {
            var alpha = Readers.select(mgr).select("Alpha").onCard((r, b) -> {
            });
            var beta = Readers.select(mgr).select("Beta").onCard((r, b) -> {
            });

            // The same name is the same reader, so it collides
            Assert.assertThrows(IllegalStateException.class,
                    () -> Readers.select(mgr).select("Alpha").onCard((r, b) -> {
                    }));
            // A hintless pass could serve either reader, so it collides with both
            Assert.assertThrows(IllegalStateException.class,
                    () -> Readers.select(mgr).onCard((r, b) -> {
                    }));

            alpha.close();
            beta.close();
            // Both released: an ambiguous pass is now free to register
            Readers.select(mgr).onCard((r, b) -> {
            }).close();

            Assert.assertThrows(IllegalArgumentException.class, () -> Readers.select(mgr).select(null));
        }
    }

    // === Wait notifier: the hook a GUI uses to raise and dismiss a "tap your card" prompt ===

    @Test
    void testWaitNotifierSeesBothPhases() throws Exception {
        var terminal = new SynthesizedCardTerminal("Notify Reader");
        terminal.present(MockBIBO.of("9000"));   // a card is already sitting in the reader
        var phases = new CopyOnWriteArrayList<String>();
        var dismissed = new AtomicInteger();

        try (var mgr = TerminalManager.managerOf(terminal)) {
            CompletableFuture.runAsync(() -> {
                sleep(200);
                terminal.yank();
                sleep(100);
                terminal.present(MockBIBO.of("9000"));
            });
            // fresh=true with a card present: wait for it to leave, then for a genuine tap
            var result = Readers.select(mgr)
                    .onWait((phase, name) -> {
                        phases.add(phase + ":" + name);
                        return dismissed::incrementAndGet;
                    })
                    .whenReady(Duration.ofSeconds(5), b -> b.transceive(HexUtils.hex2bin("00A4040000")));
            Assert.assertEquals(result, HexUtils.hex2bin("9000"));
        }
        Assert.assertEquals(phases, List.of("REMOVAL:Notify Reader", "INSERTION:Notify Reader"));
        Assert.assertEquals(dismissed.get(), 2, "each notification is dismissed when its wait ends");
    }

    // === Handler failures cross the reader executor unwrapped ===

    @Test
    void testHandlerErrorSurfacesThroughExecutor() throws Exception {
        var terminal = new SynthesizedCardTerminal("Marshal Reader");
        terminal.presentFactory(p -> MockBIBO.of("9000"), SynthesizedCardTerminal.defaultAtr());
        try (var mgr = TerminalManager.managerOf(terminal)) {
            mgr.startMonitor();
            Assert.assertTrue(mgr.awaitInitialScan(Duration.ofSeconds(5)));

            // With the monitor running, run() marshals to the reader worker. What the handler threw
            // must arrive as itself, not wrapped in the executor's ExecutionException.
            var boom = new IllegalStateException("handler blew up");
            var wrapped = Assert.expectThrows(BIBOException.class, () -> Readers.select(mgr).run(b -> {
                throw boom;
            }));
            Assert.assertSame(wrapped.getCause(), boom);

            var refused = new BIBOException("card said no");
            Assert.assertSame(Assert.expectThrows(BIBOException.class, () -> Readers.select(mgr).run(b -> {
                throw refused;
            })), refused);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
