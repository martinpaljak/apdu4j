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

import apdu4j.core.HexUtils;
import apdu4j.core.MockBIBO;
import apdu4j.pcsc.sim.SynthesizedCardTerminal;
import apdu4j.pcsc.sim.SynthesizedCardTerminals;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class MonitorTest {

    // === Card detection: insertion on existing reader and dynamic reader addition ===

    @Test
    void testMonitorDetectsCardEvents() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        var terminal = new SynthesizedCardTerminal("Contact Reader");
        terminals.addTerminal(terminal);

        try (var mgr = new TerminalManager(terminals.toFactory())) {
            var firstSeen = new CountDownLatch(1);
            var bothSeen = new CountDownLatch(2);
            var readerName = new AtomicReference<String>();

            Readers.select(mgr).onCard((reader, bibo) -> {
                readerName.compareAndSet(null, reader.name());
                bibo.transceive(HexUtils.hex2bin("00A4040000"));
                firstSeen.countDown();
                bothSeen.countDown();
            });

            Assert.assertTrue(mgr.isMonitorRunning());
            Assert.assertTrue(mgr.awaitInitialScan(Duration.ofSeconds(5)));

            // Card insertion on existing reader - wait for it before adding the second
            terminal.present(MockBIBO.of("9000"));
            Assert.assertTrue(firstSeen.await(5, TimeUnit.SECONDS), "First card event should fire");

            // Dynamic reader addition with card already present
            var newReader = new SynthesizedCardTerminal("USB Reader");
            newReader.present(MockBIBO.of("9000"));
            terminals.addTerminal(newReader);

            Assert.assertTrue(bothSeen.await(5, TimeUnit.SECONDS), "Both card events should fire");
            Assert.assertEquals(readerName.get(), "Contact Reader");
        }
    }

    // === Yank and re-insertion cycle ===

    @Test
    void testMonitorYankAndReinsert() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        var terminal = new SynthesizedCardTerminal("Contactless Reader");
        terminals.addTerminal(terminal);

        try (var mgr = new TerminalManager(terminals.toFactory())) {
            var callCount = new AtomicInteger(0);
            var firstSeen = new CountDownLatch(1);
            var secondSeen = new CountDownLatch(1);

            Readers.select(mgr).onCard((reader, bibo) -> {
                bibo.transceive(HexUtils.hex2bin("00A4040000"));
                var c = callCount.incrementAndGet();
                if (c == 1) {
                    firstSeen.countDown();
                }
                if (c == 2) {
                    secondSeen.countDown();
                }
            });

            // Factory mode: card stays present until explicit yank()
            terminal.present(proto -> MockBIBO.of("9000"), SynthesizedCardTerminal.defaultAtr());
            Assert.assertTrue(firstSeen.await(5, TimeUnit.SECONDS), "First card event should fire");

            terminal.yank();
            Assert.assertTrue(mgr.awaitReaders(
                    readers -> readers.stream().noneMatch(PCSCReader::present),
                    Duration.ofSeconds(5)));

            terminal.present(MockBIBO.of("9000"));
            Assert.assertTrue(secondSeen.await(10, TimeUnit.SECONDS), "Re-insertion should fire");
        }
    }

    // === fresh flag: controls whether already-present cards trigger onCard ===

    @Test
    void testFreshFlagOnCard() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        var terminal = new SynthesizedCardTerminal("Contactless Reader");
        terminal.present(MockBIBO.of("9000"));
        terminals.addTerminal(terminal);

        // fresh=true (default): already-present card does NOT trigger
        try (var mgr = new TerminalManager(terminals.toFactory())) {
            var fired = new AtomicInteger(0);
            var reinsertLatch = new CountDownLatch(1);

            Readers.select(mgr).onCard((reader, bibo) -> {
                fired.incrementAndGet();
                reinsertLatch.countDown();
            });

            Assert.assertTrue(mgr.awaitInitialScan(Duration.ofSeconds(5)));
            Assert.assertTrue(mgr.awaitReaders(
                    readers -> readers.stream().anyMatch(PCSCReader::present),
                    Duration.ofSeconds(5)));
            Assert.assertEquals(fired.get(), 0, "fresh=true skips already-present");

            // Yank + re-insert should fire
            terminal.yank();
            Assert.assertTrue(mgr.awaitReaders(
                    readers -> readers.stream().noneMatch(PCSCReader::present),
                    Duration.ofSeconds(5)));
            terminal.present(MockBIBO.of("9000"));
            Assert.assertTrue(reinsertLatch.await(5, TimeUnit.SECONDS));
            Assert.assertEquals(fired.get(), 1);
        }

        // fresh=false: already-present card DOES trigger (separate setup)
        var terminals2 = new SynthesizedCardTerminals();
        var terminal2 = new SynthesizedCardTerminal("Contactless Reader 2");
        terminal2.present(MockBIBO.of("9000"));
        terminals2.addTerminal(terminal2);
        try (var mgr = new TerminalManager(terminals2.toFactory())) {
            var latch = new CountDownLatch(1);
            Readers.select(mgr).fresh(false).onCard((reader, bibo) -> {
                bibo.transceive(HexUtils.hex2bin("00A4040000"));
                latch.countDown();
            });
            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS), "fresh=false triggers on present");
        }
    }

    // === fresh flag: robust against pre-started monitor ===

    @Test
    void testFreshFlagWithPrematureStartMonitor() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        var terminal = new SynthesizedCardTerminal("Contactless Reader");
        terminal.present(MockBIBO.of("9000"));
        terminals.addTerminal(terminal);

        try (var mgr = new TerminalManager(terminals.toFactory())) {
            var fired = new AtomicInteger(0);
            var reinsertLatch = new CountDownLatch(1);

            // Start monitor BEFORE registering onCard - was the uid() bug
            mgr.startMonitor();

            Readers.select(mgr).onCard((reader, bibo) -> {
                fired.incrementAndGet();
                reinsertLatch.countDown();
            });

            Assert.assertTrue(mgr.awaitInitialScan(Duration.ofSeconds(5)));
            Assert.assertTrue(mgr.awaitReaders(
                    readers -> readers.stream().anyMatch(PCSCReader::present),
                    Duration.ofSeconds(5)));
            Assert.assertEquals(fired.get(), 0, "fresh=true must skip already-present cards even when monitor started first");

            // Yank + re-insert should fire
            terminal.yank();
            Assert.assertTrue(mgr.awaitReaders(
                    readers -> readers.stream().noneMatch(PCSCReader::present),
                    Duration.ofSeconds(5)));
            terminal.present(MockBIBO.of("9000"));
            Assert.assertTrue(reinsertLatch.await(5, TimeUnit.SECONDS));
            Assert.assertEquals(fired.get(), 1);
        }
    }

    // === fresh flag: controls whenReady/connectWhenReady behavior ===

    @Test
    void testFreshFlagWhenReady() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        var terminal = new SynthesizedCardTerminal("Contact Reader");
        terminal.present(MockBIBO.of("9000"));
        terminals.addTerminal(terminal);

        // fresh=false: uses already-present card immediately
        try (var mgr = new TerminalManager(terminals.toFactory())) {
            var result = Readers.select(mgr).fresh(false)
                    .whenReady(Duration.ofSeconds(2), bibo -> bibo.transceive(HexUtils.hex2bin("00A4040000")));
            Assert.assertEquals(result, HexUtils.hex2bin("9000"));
        }

        // fresh=true (default): waits for removal then re-insertion
        terminal.present(MockBIBO.of("9000"));
        try (var mgr = new TerminalManager(terminals.toFactory())) {
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                terminal.yank();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                terminal.present(MockBIBO.of("9000"));
            });
            var result = Readers.select(mgr).whenReady(Duration.ofSeconds(5), bibo ->
                    bibo.transceive(HexUtils.hex2bin("00A4040000")));
            Assert.assertEquals(result, HexUtils.hex2bin("9000"));
        }
    }

    // === Card vs reader removal ordering ===

    @Test
    void testCardRemovedBeforeReader() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        var terminal = new SynthesizedCardTerminal("Reader A");
        terminal.present(MockBIBO.of("9000"));
        terminals.addTerminal(terminal);

        try (var mgr = new TerminalManager(terminals.toFactory())) {
            // awaitReaders auto-starts the monitor
            Assert.assertTrue(mgr.awaitReaders(
                    readers -> readers.stream().anyMatch(r -> "Reader A".equals(r.name()) && r.present()),
                    Duration.ofSeconds(5)));

            // Yank card - reader stays visible
            terminal.yank();
            Assert.assertTrue(mgr.awaitReaders(
                    readers -> readers.stream().anyMatch(r -> "Reader A".equals(r.name()) && !r.present()),
                    Duration.ofSeconds(5)));

            // Remove reader entirely
            terminals.yank("Reader A");
            Assert.assertTrue(mgr.awaitReaders(
                    readers -> readers.stream().noneMatch(r -> "Reader A".equals(r.name())),
                    Duration.ofSeconds(5)));
        }
    }

    // === Executor dispatch: monitor-backed run() and timeout-based waiting ===

    @Test
    void testMonitorBackedRun() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        var terminal = new SynthesizedCardTerminal("Monitor Run Reader");
        terminal.present(MockBIBO.of("9000"));
        terminals.addTerminal(terminal);

        try (var mgr = new TerminalManager(terminals.toFactory())) {
            // awaitInitialScan auto-starts the monitor
            Assert.assertTrue(mgr.awaitInitialScan(Duration.ofSeconds(5)));
            Assert.assertEquals(
                    Readers.select(mgr).run(bibo -> bibo.transceive(HexUtils.hex2bin("00A4040000"))),
                    HexUtils.hex2bin("9000"));
        }
    }

    @Test
    void testTimeoutBasedWaiting() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        var terminal = new SynthesizedCardTerminal("Timeout Reader");
        terminals.addTerminal(terminal);

        try (var mgr = new TerminalManager(terminals.toFactory())) {
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                terminal.present(MockBIBO.of("9000"));
            });

            // connectWhenReady returns BIBO proxy
            var bibo = Readers.select(mgr).connectWhenReady(Duration.ofSeconds(5));
            Assert.assertEquals(bibo.transceive(HexUtils.hex2bin("00A4040000")), HexUtils.hex2bin("9000"));
            bibo.close();
        }

        // whenReady with lambda
        terminal.yank();
        try (var mgr = new TerminalManager(terminals.toFactory())) {
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                terminal.present(MockBIBO.of("9000"));
            });
            var result = Readers.select(mgr).whenReady(Duration.ofSeconds(5), bibo ->
                    bibo.transceive(HexUtils.hex2bin("00A4040000")));
            Assert.assertEquals(result, HexUtils.hex2bin("9000"));
        }
    }
}
