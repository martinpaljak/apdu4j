// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc.sim;

import apdu4j.core.MockBIBO;
import org.testng.annotations.Test;

import javax.smartcardio.CardException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static javax.smartcardio.CardTerminals.State.ALL;
import static javax.smartcardio.CardTerminals.State.CARD_ABSENT;
import static javax.smartcardio.CardTerminals.State.CARD_INSERTION;
import static javax.smartcardio.CardTerminals.State.CARD_PRESENT;
import static javax.smartcardio.CardTerminals.State.CARD_REMOVAL;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class SynthesizedCardTerminalsTest {

    // Presence is absolute; insertion and removal are deltas from the previous wait.
    @Test
    public void listReportsPresenceThenDeltas() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        var loaded = new SynthesizedCardTerminal("Present Reader");
        loaded.present(MockBIBO.of("9000"));
        var empty = new SynthesizedCardTerminal("Empty Reader");
        terminals.addTerminal(loaded);
        terminals.addTerminal(empty);

        // Before the first wait there is nothing to diff against, so both delta lists fall back
        // to raw presence.
        assertEquals(terminals.list(ALL).size(), 2);
        assertEquals(terminals.list(CARD_PRESENT).size(), 1);
        assertEquals(terminals.list(CARD_ABSENT).size(), 1);
        assertEquals(terminals.list(CARD_INSERTION).size(), 1);
        assertEquals(terminals.list(CARD_REMOVAL).size(), 1);

        // Drain the setup changes to settle the baseline.
        while (terminals.waitForChange(50)) {
        }

        // A new card is listed as an insertion, once.
        empty.present(MockBIBO.of("9000"));
        assertTrue(terminals.waitForChange(0));
        assertEquals(terminals.list(CARD_INSERTION).size(), 1);
        assertEquals(terminals.list(CARD_REMOVAL).size(), 0);
        assertFalse(terminals.waitForChange(50));
        assertEquals(terminals.list(CARD_INSERTION).size(), 0);
        assertEquals(terminals.list(CARD_PRESENT).size(), 2);

        // A departed card is listed as a removal.
        empty.yank();
        assertTrue(terminals.waitForChange(0));
        assertEquals(terminals.list(CARD_REMOVAL).size(), 1);
        assertEquals(terminals.list(CARD_INSERTION).size(), 0);
    }

    // Registry contract: names are unique, a removed terminal is unwired, and waitForChange
    // rejects what PC/SC rejects.
    @Test
    public void registryContract() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        // No terminals: contract requires IllegalStateException
        expectThrows(IllegalStateException.class, () -> terminals.waitForChange(200));

        var t1 = new SynthesizedCardTerminal("t1");
        terminals.addTerminal(t1);
        expectThrows(IllegalArgumentException.class, () -> terminals.waitForChange(-1));
        expectThrows(IllegalArgumentException.class, () -> terminals.addTerminal(new SynthesizedCardTerminal("t1")));

        // A yanked terminal's later present() must not wake waitForChange.
        var t2 = new SynthesizedCardTerminal("t2");
        terminals.addTerminal(t2);
        terminals.yank("t2");
        assertEquals(terminals.list(ALL).size(), 1);
        while (terminals.waitForChange(1)) {
        }
        t2.present(MockBIBO.of("9000"));
        assertFalse(terminals.waitForChange(100));

        // Yanking a name that is not registered is a no-op.
        terminals.yank("nosuch");
        assertEquals(terminals.list(ALL).size(), 1);
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
}
