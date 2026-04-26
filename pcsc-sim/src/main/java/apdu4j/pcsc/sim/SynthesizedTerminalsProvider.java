// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc.sim;

import javax.smartcardio.CardTerminals;
import javax.smartcardio.TerminalFactorySpi;
import java.security.Provider;

public final class SynthesizedTerminalsProvider extends Provider {

    public SynthesizedTerminalsProvider() {
        super("SynthesizedTerminals", "1.0", "Synthesized javax.smartcardio from apdu4j");
        put("TerminalFactory.PC/SC", SynthesizedSpi.class.getName());
    }

    public static class SynthesizedSpi extends TerminalFactorySpi {
        private final SynthesizedCardTerminals terminals;

        public SynthesizedSpi(Object parameter) {
            if (parameter instanceof SynthesizedCardTerminals sct) {
                this.terminals = sct;
            } else {
                throw new IllegalArgumentException("Expected SynthesizedCardTerminals");
            }
        }

        @Override
        public CardTerminals engineTerminals() {
            return terminals;
        }
    }
}
