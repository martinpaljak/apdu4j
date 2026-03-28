/*
 * Copyright (c) 2025-present Martin Paljak <martin@martinpaljak.net>
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
package apdu4j.prefs;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.testng.Assert.*;

public class PreferenceProviderTest {

    static final Preference.Default<String> PROTOCOL = Preference.of("reader.protocol", String.class, "*", false);
    static final Preference.Default<Boolean> EXCLUSIVE = Preference.of("reader.exclusive", Boolean.class, false, false);
    static final Preference.Default<Integer> TIMEOUT = Preference.of("reader.timeout", Integer.class, 5000, false);
    static final Preference.Parameter<String> SESSION_KEY = Preference.parameter("session.key", String.class, true);

    // === Built-in providers ===

    @Test
    void environmentProvider() {
        var env = Map.of("READER_PROTOCOL", "T=1", "MY_SETTING", "value");
        var provider = PreferenceProvider.environment(env::get);

        // Dots converted to underscores, uppercased
        assertEquals(provider.resolve(PROTOCOL).orElseThrow(), "T=1");
        // Hyphens also converted
        var hyphenated = Preference.of("my-setting", String.class, "d", false);
        assertEquals(provider.resolve(hyphenated).orElseThrow(), "value");
        // Missing returns empty
        assertTrue(provider.resolve(TIMEOUT).isEmpty());
    }

    @Test
    void systemPropertiesProvider() {
        System.setProperty("reader.protocol", "T=0");
        try {
            assertEquals(PreferenceProvider.systemProperties().resolve(PROTOCOL).orElseThrow(), "T=0");
        } finally {
            System.clearProperty("reader.protocol");
        }
    }

    @Test
    void propertiesAndMapProviders() {
        // Properties provider
        var props = new Properties();
        props.setProperty("reader.protocol", "T=CL");
        assertEquals(PreferenceProvider.properties(props).resolve(PROTOCOL).orElseThrow(), "T=CL");

        // Map provider
        var map = PreferenceProvider.map(Map.of("reader.protocol", "DIRECT"));
        assertEquals(map.resolve(PROTOCOL).orElseThrow(), "DIRECT");
        assertTrue(map.resolve(TIMEOUT).isEmpty());
    }

    // === Chaining with orElse ===

    @Test
    void orElseChaining() {
        var primary = PreferenceProvider.map(Map.of("reader.protocol", "T=1"));
        var fallback = PreferenceProvider.map(Map.of("reader.protocol", "T=0", "reader.timeout", "3000"));
        var chained = primary.orElse(fallback);

        // Primary wins when present
        assertEquals(chained.resolve(PROTOCOL).orElseThrow(), "T=1");
        // Fallback used when primary is empty
        assertEquals(chained.resolve(TIMEOUT).orElseThrow(), "3000");
        // Both empty returns empty
        assertTrue(chained.resolve(EXCLUSIVE).isEmpty());
    }

    // === Type coercion ===

    @Test
    void typeCoercion() {
        assertEquals(PreferenceProvider.coerce(String.class, "hello"), "hello");
        assertEquals(PreferenceProvider.coerce(Boolean.class, "true"), Boolean.TRUE);
        assertEquals(PreferenceProvider.coerce(Boolean.class, "garbage"), Boolean.FALSE);
        assertEquals(PreferenceProvider.coerce(Integer.class, "42"), 42);
        assertEquals(PreferenceProvider.coerce(Long.class, "9999999999"), 9999999999L);
        assertEquals((byte[]) PreferenceProvider.coerce(byte[].class, "cafe"), new byte[]{(byte) 0xCA, (byte) 0xFE});
    }

    @Test
    void typeCoercionRejectsInvalid() {
        assertThrows(NumberFormatException.class, () -> PreferenceProvider.coerce(Integer.class, "xyz"));
        assertThrows(IllegalArgumentException.class, () -> PreferenceProvider.coerce(Double.class, "3.14"));
    }

    // === resolve() snapshot ===

    @Test
    void resolveCreatesTypedSnapshot() {
        var provider = PreferenceProvider.map(Map.of(
                "reader.protocol", "T=1",
                "reader.exclusive", "true",
                "reader.timeout", "3000"
        ));
        var prefs = provider.resolve(PROTOCOL, EXCLUSIVE, TIMEOUT);

        assertEquals(prefs.get(PROTOCOL), "T=1");
        assertEquals(prefs.get(EXCLUSIVE), Boolean.TRUE);
        assertEquals(prefs.get(TIMEOUT), Integer.valueOf(3000));
        assertEquals(prefs.size(), 3);
    }

    @Test
    void resolveSkipsMissingAndInvalid() {
        var provider = PreferenceProvider.map(Map.of(
                "reader.protocol", "T=1",
                "reader.timeout", "notanumber"
        ));
        // Missing EXCLUSIVE and invalid TIMEOUT are both skipped
        var prefs = provider.resolve(PROTOCOL, EXCLUSIVE, TIMEOUT);
        assertEquals(prefs.size(), 1);
        assertTrue(prefs.valueOf(EXCLUSIVE).isEmpty());
        assertTrue(prefs.valueOf(TIMEOUT).isEmpty());
    }

    @Test
    void resolveRespectsValidators() {
        var bounded = Preference.of("port", Integer.class, 8080, false, p -> p > 0 && p < 65536);
        // Valid
        assertEquals(PreferenceProvider.map(Map.of("port", "443")).resolve(List.of(bounded)).get(bounded),
                Integer.valueOf(443));
        // Invalid - silently skipped
        assertTrue(PreferenceProvider.map(Map.of("port", "99999")).resolve(List.of(bounded)).isEmpty());
    }

    @Test
    void resolveParameterAndByteArray() {
        var provider = PreferenceProvider.map(Map.of("session.key", "abc", "key.data", "deadbeef"));
        var keyData = Preference.of("key.data", byte[].class, new byte[0], false);

        var prefs = provider.resolve(List.of(SESSION_KEY, keyData));
        assertEquals(prefs.valueOf(SESSION_KEY).orElseThrow(), "abc");
        assertEquals(prefs.get(keyData), new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});
    }

    // === Preferences.fromEnvironment convenience ===

    @Test
    void fromEnvironmentConvenience() {
        // Smoke test - uses real System.getenv(), verify no exceptions
        assertNotNull(Preferences.fromEnvironment(PROTOCOL, EXCLUSIVE));
        assertNotNull(Preferences.fromEnvironment(List.of(PROTOCOL)));
    }
}
