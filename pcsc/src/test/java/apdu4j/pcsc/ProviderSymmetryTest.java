// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
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
