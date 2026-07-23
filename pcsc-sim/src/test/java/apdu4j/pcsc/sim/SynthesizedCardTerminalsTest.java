// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc.sim;

import apdu4j.core.MockBIBO;
import org.testng.annotations.Test;

import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminals;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static javax.smartcardio.CardTerminals.State.CARD_INSERTION;
import static javax.smartcardio.CardTerminals.State.CARD_REMOVAL;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class SynthesizedCardTerminalsTest {

    // Concurrent addTerminal of one name inserts it exactly once.
    @Test
    public void addTerminalRejectsDuplicateUnderConcurrency() throws Exception {
        for (int round = 0; round < 200; round++) {
            var terminals = new SynthesizedCardTerminals();
            int n = 64;
            var barrier = new CyclicBarrier(n);
            var threads = new ArrayList<Thread>();
            for (int i = 0; i < n; i++) {
                var th = new Thread(() -> {
                    try {
                        barrier.await();
                        terminals.addTerminal(new SynthesizedCardTerminal("dup"));
                    } catch (IllegalArgumentException expected) {
                        // losing threads are rejected
                    } catch (Exception ignored) {
                        // barrier interruption is irrelevant to the assertion
                    }
                });
                threads.add(th);
                th.start();
            }
            for (var th : threads) {
                th.join();
            }
            long count = terminals.list(CardTerminals.State.ALL).stream()
                    .filter(t -> t.getName().equals("dup")).count();
            assertEquals(count, 1, "duplicate terminals inserted in round " + round);
        }
    }

    // A yanked terminal's later present() must not wake waitForChange.
    @Test
    public void yankUnwiresRemovedTerminal() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        var t1 = new SynthesizedCardTerminal("t1");
        var t2 = new SynthesizedCardTerminal("t2");
        terminals.addTerminal(t1);
        terminals.addTerminal(t2);
        terminals.yank("t2");
        while (terminals.waitForChange(1)) {
        }
        t2.present(MockBIBO.of("9000"));
        assertFalse(terminals.waitForChange(100));
    }

    // waitForChange reports deltas from the last wait.
    @Test
    public void waitForChangeReportsDeltasNotPresence() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        var t = new SynthesizedCardTerminal("t");
        terminals.addTerminal(t);
        // Drain addTerminal's own change to settle the baseline.
        while (terminals.waitForChange(50)) {
        }

        // A new card is listed as an insertion.
        t.present(MockBIBO.of("9000"));
        assertTrue(terminals.waitForChange(0));
        assertEquals(terminals.list(CARD_INSERTION).size(), 1);
        assertEquals(terminals.list(CARD_REMOVAL).size(), 0);

        // An unchanged card times out with no insertion.
        assertFalse(terminals.waitForChange(50));
        assertEquals(terminals.list(CARD_INSERTION).size(), 0);

        // A departed card is listed as a removal.
        t.yank();
        assertTrue(terminals.waitForChange(0));
        assertEquals(terminals.list(CARD_REMOVAL).size(), 1);
        assertEquals(terminals.list(CARD_INSERTION).size(), 0);
    }

    // An interrupt in waitForChange throws CardException and keeps the flag.
    @Test
    public void waitForChangeInterruptPreservesFlag() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        terminals.addTerminal(new SynthesizedCardTerminal("t"));
        while (terminals.waitForChange(50)) { // drain the setup change and settle the baseline
        }
        var caught = new AtomicReference<Throwable>();
        var wasInterrupted = new AtomicBoolean();
        var waiter = new Thread(() -> {
            try {
                terminals.waitForChange(0);
            } catch (Throwable e) {
                caught.set(e);
                wasInterrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        waiter.start();
        Thread.sleep(50);
        waiter.interrupt();
        waiter.join();
        assertTrue(caught.get() instanceof CardException, "expected CardException but got " + caught.get());
        assertTrue(wasInterrupted.get(), "interrupt status must be preserved");
    }

    @Test
    public void waitForChangeContractThrows() throws CardException {
        var terminals = new SynthesizedCardTerminals();
        // No terminals: contract requires IllegalStateException
        expectThrows(IllegalStateException.class, () -> terminals.waitForChange(200));
        // Negative timeout: contract requires IllegalArgumentException
        terminals.addTerminal(new SynthesizedCardTerminal("sim"));
        expectThrows(IllegalArgumentException.class, () -> terminals.waitForChange(-1));
    }
}
