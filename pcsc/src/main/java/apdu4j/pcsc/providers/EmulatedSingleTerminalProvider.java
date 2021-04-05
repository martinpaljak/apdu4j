/*
 * Copyright (c) 2019 Martin Paljak
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
package apdu4j.pcsc.providers;

import apdu4j.pcsc.TerminalManager;

import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CardTerminals;
import javax.smartcardio.TerminalFactorySpi;
import java.security.Provider;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public abstract class EmulatedSingleTerminalProvider extends Provider {
    static final long serialVersionUID = -1813264769968105172L;

    @SuppressWarnings("deprecation") // 11 would prefer String, String, String super
    public EmulatedSingleTerminalProvider(Class<? extends EmulatedTerminalFactorySpi> clazz) {
        super(String.format("Emulated %s", clazz.getSimpleName()), 0.1d, "EmulatedTerminalProvider from apdu4j/" + TerminalManager.getVersion());
        put("TerminalFactory.PC/SC", clazz.getName());
    }

    public static class EmulatedTerminalFactorySpi extends TerminalFactorySpi {
        transient volatile CardTerminal instance;

        Object parameter;

        public EmulatedTerminalFactorySpi(Object parameter) {
            this.parameter = parameter;
        }

        @Override
        public CardTerminals engineTerminals() {
            if (instance == null) {
                instance = getTheTerminal();
            }
            return new EmulatedCardTerminals(Collections.unmodifiableList(Arrays.asList(instance)));
        }

        protected CardTerminal getTheTerminal() {
            throw new IllegalStateException("getTheTerminal() must be implemented!");
        }
    }


    static class EmulatedCardTerminals extends CardTerminals {
        final List<CardTerminal> terminals;

        EmulatedCardTerminals(List<CardTerminal> terminals) {
            this.terminals = terminals;
        }

        @Override
        public List<CardTerminal> list(State state) {
            switch (state) {
                case ALL:
                case CARD_PRESENT:
                case CARD_INSERTION:
                    return terminals;
            }
            return Collections.emptyList();
        }

        @Override
        public boolean waitForChange(long l) throws CardException {
            // Do nothing
            try {
                Thread.sleep(l);
                return false;
            } catch (InterruptedException e) {
                throw new CardException("Interrupted", e);
            }
        }
    }
}