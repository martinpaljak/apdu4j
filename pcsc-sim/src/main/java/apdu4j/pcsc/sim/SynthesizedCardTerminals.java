// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc.sim;

import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CardTerminals;
import javax.smartcardio.TerminalFactory;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class SynthesizedCardTerminals extends CardTerminals {

    private final CopyOnWriteArrayList<SynthesizedCardTerminal> terminals = new CopyOnWriteArrayList<>();
    private final Semaphore changeSignal = new Semaphore(0);

    public void addTerminal(SynthesizedCardTerminal terminal) {
        if (terminals.stream().anyMatch(t -> t.getName().equals(terminal.getName()))) {
            throw new IllegalArgumentException("Terminal with name '%s' already exists".formatted(terminal.getName()));
        }
        terminal.setOnChange(() -> changeSignal.release());
        terminals.add(terminal);
        changeSignal.release();
    }

    public void yank(String name) {
        for (var t : terminals) {
            if (t.getName().equals(name)) {
                t.yank();
                terminals.remove(t);
                changeSignal.release();
                break;
            }
        }
    }

    @Override
    public List<CardTerminal> list(State state) throws CardException {
        return switch (state) {
            case ALL -> List.copyOf(terminals);
            case CARD_PRESENT, CARD_INSERTION -> terminals.stream()
                    .filter(SynthesizedCardTerminal::isCardPresent)
                    .map(CardTerminal.class::cast).toList();
            case CARD_ABSENT, CARD_REMOVAL -> terminals.stream()
                    .filter(t -> !t.isCardPresent())
                    .map(CardTerminal.class::cast).toList();
        };
    }

    @Override
    public boolean waitForChange(long timeout) throws CardException {
        try {
            if (timeout == 0) {
                changeSignal.acquire(); // 0 = wait indefinitely per PC/SC spec
                changeSignal.drainPermits();
                return true;
            }
            var changed = changeSignal.tryAcquire(timeout, TimeUnit.MILLISECONDS);
            if (changed) {
                changeSignal.drainPermits();
            }
            return changed;
        } catch (InterruptedException e) {
            throw new CardException("Interrupted", e);
        }
    }

    public TerminalFactory toFactory() {
        try {
            return TerminalFactory.getInstance("PC/SC", this, new SynthesizedTerminalsProvider());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
