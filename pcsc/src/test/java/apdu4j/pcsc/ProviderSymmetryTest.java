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

import jnasmartcardio.Smartcardio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.TerminalFactory;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import static org.testng.Assert.assertEquals;

public class ProviderSymmetryTest {
    private static final Logger logger = LoggerFactory.getLogger(ProviderSymmetryTest.class);

    sealed interface Outcome {
        record Readers(List<String> names) implements Outcome {
        }

        record Unavailable(String reason) implements Outcome {
        }
    }

    // Probe a factory, normalizing "no readers" errors to empty list
    static Outcome probe(TerminalFactory factory) {
        try {
            var terminals = factory.terminals();
            var names = terminals.list().stream()
                    .map(CardTerminal::getName)
                    .sorted()
                    .toList();
            return new Outcome.Readers(names);
        } catch (CardException e) {
            var msg = SCard.getExceptionMessage(e);
            if (SCard.SCARD_E_NO_READERS_AVAILABLE.equals(msg)) {
                return new Outcome.Readers(List.of());
            }
            return new Outcome.Unavailable(msg);
        } catch (Exception e) {
            // JNA throws EstablishContextException (unchecked) when pcscd is down
            return new Outcome.Unavailable(SCard.getExceptionMessage(e));
        }
    }

    @Test
    void providerSymmetry() {
        // JNA (jnasmartcardio) -explicit
        Outcome jna;
        try {
            var factory = TerminalFactory.getInstance("PC/SC", null, new Smartcardio());
            jna = probe(factory);
        } catch (NoSuchAlgorithmException e) {
            jna = new Outcome.Unavailable("JNA: " + e.getMessage());
        }
        logger.info("JNA: {}", jna);

        // SunPCSC -implicit (JVM default)
        TerminalManager.fixPlatformPaths();
        var defaultFactory = TerminalFactory.getDefault();
        var sun = TerminalManager.isNoneProvider(defaultFactory)
                ? new Outcome.Unavailable("NoneCardTerminals")
                : probe(defaultFactory);
        logger.info("SunPCSC: {}", sun);

        // Assert equality when both providers return reader lists
        if (jna instanceof Outcome.Readers j && sun instanceof Outcome.Readers s) {
            assertEquals(j.names(), s.names(), "JNA and SunPCSC should see the same readers");
        }
    }
}
