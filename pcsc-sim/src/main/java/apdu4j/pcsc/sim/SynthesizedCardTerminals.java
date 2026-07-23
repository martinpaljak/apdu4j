// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc.sim;

import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CardTerminals;
import javax.smartcardio.TerminalFactory;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class SynthesizedCardTerminals extends CardTerminals {

    private final CopyOnWriteArrayList<SynthesizedCardTerminal> terminals = new CopyOnWriteArrayList<>();
    // A permit hints at change without counting events.
    private final Semaphore changeSignal = new Semaphore(0);
    private Set<String> present;
    private Set<String> lastInserted = Set.of();
    private Set<String> lastRemoved = Set.of();

    public synchronized void addTerminal(SynthesizedCardTerminal terminal) {
        if (terminals.stream().anyMatch(t -> t.getName().equals(terminal.getName()))) {
            throw new IllegalArgumentException("Terminal with name '%s' already exists".formatted(terminal.getName()));
        }
        terminal.setOnChange(changeSignal::release);
        terminals.add(terminal);
        changeSignal.release();
    }

    public synchronized void yank(String name) {
        for (var t : terminals) {
            if (t.getName().equals(name)) {
                t.setOnChange(null);
                t.yank();
                terminals.remove(t);
                changeSignal.release();
                break;
            }
        }
    }

    @Override
    public synchronized List<CardTerminal> list(State state) throws CardException {
        // Before the first wait insertion and removal fall back to raw presence.
        return switch (state) {
            case ALL -> List.copyOf(terminals);
            case CARD_PRESENT -> byPresence(true);
            case CARD_ABSENT -> byPresence(false);
            case CARD_INSERTION -> present == null ? byPresence(true) : byName(lastInserted);
            case CARD_REMOVAL -> present == null ? byPresence(false) : byName(lastRemoved);
        };
    }

    private List<CardTerminal> byPresence(boolean wantPresent) {
        return terminals.stream().filter(t -> t.isCardPresent() == wantPresent)
                .map(CardTerminal.class::cast).toList();
    }

    private List<CardTerminal> byName(Set<String> names) {
        return terminals.stream().filter(t -> names.contains(t.getName()))
                .map(CardTerminal.class::cast).toList();
    }

    private Set<String> presentNames() {
        return terminals.stream().filter(SynthesizedCardTerminal::isCardPresent)
                .map(CardTerminal::getName).collect(Collectors.toSet());
    }

    @Override
    public boolean waitForChange(long timeout) throws CardException {
        if (timeout < 0) {
            throw new IllegalArgumentException("Timeout must not be negative");
        }
        if (terminals.isEmpty()) {
            throw new IllegalStateException("No terminals");
        }
        try {
            // The snapshot below only feeds list(INSERTION/REMOVAL).
            boolean changed;
            if (timeout == 0) {
                changeSignal.acquire(); // 0 = wait indefinitely per PC/SC spec
                changed = true;
            } else {
                changed = changeSignal.tryAcquire(timeout, TimeUnit.MILLISECONDS);
            }
            changeSignal.drainPermits(); // presence is re-read below
            synchronized (this) {
                var current = presentNames();
                // A card present at the first wait counts as an insertion.
                var base = present == null ? Set.<String>of() : present;
                lastInserted = difference(current, base);
                lastRemoved = difference(base, current);
                present = current;
            }
            return changed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CardException("Interrupted", e);
        }
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        return a.stream().filter(n -> !b.contains(n)).collect(Collectors.toSet());
    }

    public TerminalFactory toFactory() {
        try {
            return TerminalFactory.getInstance("PC/SC", this, new SynthesizedTerminalsProvider());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
