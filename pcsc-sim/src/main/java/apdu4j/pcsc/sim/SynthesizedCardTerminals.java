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
