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
import java.util.function.Function;

public abstract class EmulatedTerminalProvider extends Provider {
    static final long serialVersionUID = -1813264769968105172L;

    static transient volatile Function<Object, CardTerminal> provider;
    static transient volatile CardTerminal instance;

    private transient static final String PROVIDER_NAME = "Emulated";

    @SuppressWarnings("deprecation") // 11 would prefer String, String, String super
    public EmulatedTerminalProvider(Function<Object, CardTerminal> provider) {
        super(PROVIDER_NAME, 0.1d, "EmulatedTerminalProvider from apdu4j/" + TerminalManager.getVersion());
        EmulatedTerminalProvider.provider = provider;
        put("TerminalFactory.PC/SC", EmulatedTerminalProviderSpi.class.getName());
    }

    public static class EmulatedTerminalProviderSpi extends TerminalFactorySpi {
        Object parameter;

        public EmulatedTerminalProviderSpi(Object parameter) {
            this.parameter = parameter;
        }

        @Override
        public CardTerminals engineTerminals() {
            if (instance == null) {
                instance = provider.apply(parameter);
            }
            return new EmulatedCardTerminals(Collections.unmodifiableList(Arrays.asList(instance)));
        }
    }


    static class EmulatedCardTerminals extends CardTerminals {
        final List<CardTerminal> terminals;

        EmulatedCardTerminals(List<CardTerminal> terminals) {
            this.terminals = terminals;
        }

        @Override
        public List<CardTerminal> list(State state) {
            return terminals; // FIXME: state ?
        }

        @Override
        public boolean waitForChange(long l) throws CardException {
            try {
                Thread.sleep(l);
                return false;
            } catch (InterruptedException e) {
                throw new CardException("Interrupted: " + e.getMessage(), e);
            }
        }
    }
}