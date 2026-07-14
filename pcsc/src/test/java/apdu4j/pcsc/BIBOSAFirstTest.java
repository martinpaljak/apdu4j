// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import apdu4j.core.CardInfo;

import apdu4j.core.BIBO;
import apdu4j.core.BIBOSA;
import apdu4j.core.HexUtils;
import apdu4j.core.MockBIBO;
import apdu4j.pcsc.sim.SynthesizedCardTerminal;
import apdu4j.pcsc.sim.SynthesizedCardTerminals;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.function.Function;

// The Readers framework hands out BIBOSA everywhere: the transport carries a typed Preferences
// sidecar (reader name, ATR, negotiated protocol) that survives both managed sessions and the
// per-reader executor proxy used when a monitor is running.
public class BIBOSAFirstTest {

    private static TerminalManager managerWithCard(String reader) {
        var terminals = new SynthesizedCardTerminals();
        var terminal = new SynthesizedCardTerminal(reader);
        terminal.present(MockBIBO.of("9000"));
        terminals.addTerminal(terminal);
        return new TerminalManager(terminals.toFactory());
    }

    private static void assertSidecar(BIBOSA stack, String reader) {
        var prefs = stack.preferences();
        Assert.assertEquals(prefs.valueOf(CardInfo.READER_NAME).orElse(null), reader);
        Assert.assertTrue(prefs.valueOf(CardInfo.ATR).isPresent(), "ATR present in sidecar");
        Assert.assertTrue(prefs.valueOf(CardInfo.NEGOTIATED_PROTOCOL).isPresent(), "negotiated protocol present in sidecar");
    }

    @Test
    void managedRunHandsBibosaWithSidecar() {
        try (var mgr = managerWithCard("Contact Reader")) {
            // run() now hands out a BIBOSA - capture it and inspect the sidecar
            BIBOSA stack = Readers.select(mgr).run(s -> s);
            assertSidecar(stack, "Contact Reader");
        }
    }

    @Test
    void unmanagedConnectReturnsBibosaWithSidecar() {
        try (var mgr = managerWithCard("Contact Reader")) {
            BIBOSA stack = Readers.select(mgr).connect();
            try {
                assertSidecar(stack, "Contact Reader");
            } finally {
                stack.close();
            }
        }
    }

    // Regression for the executor-proxy sidecar drop: with a monitor running, connect() marshals
    // through the per-reader executor. That proxy used to return a plain anonymous BIBO and erase
    // the preferences; it must now re-wrap as a BIBOSA preserving them.
    @Test
    void connectPreservesSidecarThroughExecutorProxy() throws Exception {
        try (var mgr = managerWithCard("Contact Reader")) {
            mgr.startMonitor();
            Assert.assertTrue(mgr.isMonitorRunning(), "monitor must be running for the marshaled path");
            // With the monitor running, mgr.readers() serves the monitor's snapshot - wait for the
            // initial scan to populate it (and see the present card) before resolving the reader.
            Assert.assertTrue(mgr.awaitInitialScan(Duration.ofSeconds(5)));
            Assert.assertTrue(mgr.awaitReaders(
                    readers -> readers.stream().anyMatch(PCSCReader::present),
                    Duration.ofSeconds(5)));
            BIBOSA stack = Readers.select(mgr).connect();
            try {
                assertSidecar(stack, "Contact Reader");
                // Still usable as a BIBO through the marshaling proxy
                Assert.assertEquals(stack.transceive(HexUtils.hex2bin("00A4040000")), HexUtils.hex2bin("9000"));
            } finally {
                stack.close();
            }
        }
    }

    // Variance guarantee: a plain Function<BIBO, T> that ignores the sidecar still drops straight in.
    @Test
    void plainBiboFunctionStillAccepted() {
        try (var mgr = managerWithCard("Contact Reader")) {
            Function<BIBO, byte[]> plain = b -> b.transceive(HexUtils.hex2bin("00A4040000"));
            var result = Readers.select(mgr).run(plain);
            Assert.assertEquals(result, HexUtils.hex2bin("9000"));
        }
    }
}
