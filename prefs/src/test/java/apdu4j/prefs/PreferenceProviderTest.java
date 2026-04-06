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
        var sourced = provider.resolve(PROTOCOL).orElseThrow();
        assertEquals(sourced.value(), "T=1");
        assertEquals(sourced.source(), "env");

        // Hyphens also converted
        var hyphenated = Preference.of("my-setting", String.class, "d", false);
        var sourced2 = provider.resolve(hyphenated).orElseThrow();
        assertEquals(sourced2.value(), "value");
        assertEquals(sourced2.source(), "env");

        // Missing returns empty
        assertTrue(provider.resolve(TIMEOUT).isEmpty());
    }

    @Test
    void systemPropertiesProvider() {
        System.setProperty("reader.protocol", "T=0");
        try {
            var sourced = PreferenceProvider.systemProperties().resolve(PROTOCOL).orElseThrow();
            assertEquals(sourced.value(), "T=0");
            assertEquals(sourced.source(), "prop");
        } finally {
            System.clearProperty("reader.protocol");
        }
    }

    @Test
    void propertiesAndMapProviders() {
        // Properties provider
        var props = new Properties();
        props.setProperty("reader.protocol", "T=CL");
        var sourced = PreferenceProvider.properties(props).resolve(PROTOCOL).orElseThrow();
        assertEquals(sourced.value(), "T=CL");
        assertEquals(sourced.source(), "file");

        // Map provider
        var map = PreferenceProvider.map(Map.of("reader.protocol", "DIRECT"), "cli");
        var sourced2 = map.resolve(PROTOCOL).orElseThrow();
        assertEquals(sourced2.value(), "DIRECT");
        assertEquals(sourced2.source(), "cli");
        assertTrue(map.resolve(TIMEOUT).isEmpty());
    }

    @Test
    void mapProviderWithMixedTypes() {
        // Map<String, ?> accepts typed values
        var map = PreferenceProvider.map(Map.of(
                "reader.protocol", "T=1",
                "reader.timeout", 3000
        ), "cli");
        var prefs = new Preferences().withProvider(map);

        assertEquals(prefs.get(PROTOCOL), "T=1");
        // Integer value passes through convert's isInstance check
        assertEquals(prefs.get(TIMEOUT), Integer.valueOf(3000));
    }

    @Test
    void typedProvider() {
        var map = Map.<Preference<?>, Object>of(
                PROTOCOL, "T=1",
                EXCLUSIVE, true,
                TIMEOUT, 3000
        );
        var prefs = new Preferences().withProvider(PreferenceProvider.typed(map, "session"));

        assertEquals(prefs.get(PROTOCOL), "T=1");
        assertEquals(prefs.get(EXCLUSIVE), Boolean.TRUE);
        assertEquals(prefs.get(TIMEOUT), Integer.valueOf(3000));
    }

    @Test
    void typedProviderMissing() {
        var map = Map.<Preference<?>, Object>of(PROTOCOL, "T=1");
        var prefs = new Preferences().withProvider(PreferenceProvider.typed(map, "session"));

        // Present key
        assertEquals(prefs.get(PROTOCOL), "T=1");
        // Missing key falls back to default
        assertEquals(prefs.get(TIMEOUT), Integer.valueOf(5000));
        assertTrue(prefs.valueOf(TIMEOUT).isEmpty());
    }

    // === Chaining with orElse ===

    @Test
    void orElseChaining() {
        var primary = PreferenceProvider.map(Map.of("reader.protocol", "T=1"), "cli");
        var fallback = PreferenceProvider.map(Map.of("reader.protocol", "T=0", "reader.timeout", "3000"), "file");
        var chained = primary.orElse(fallback);

        // Primary wins when present - carries primary's source
        var sourced = chained.resolve(PROTOCOL).orElseThrow();
        assertEquals(sourced.value(), "T=1");
        assertEquals(sourced.source(), "cli");

        // Fallback used when primary is empty - carries fallback's source
        var sourced2 = chained.resolve(TIMEOUT).orElseThrow();
        assertEquals(sourced2.value(), "3000");
        assertEquals(sourced2.source(), "file");

        // Both empty returns empty
        assertTrue(chained.resolve(EXCLUSIVE).isEmpty());
    }

    @Test
    void orElseChainingWithConversion() {
        // orElse chain feeding into withProvider for type conversion
        var primary = PreferenceProvider.map(Map.of("reader.timeout", "3000"), "cli");
        var fallback = PreferenceProvider.map(Map.of("reader.exclusive", "true"), "file");
        var prefs = new Preferences().withProvider(primary.orElse(fallback));

        assertEquals(prefs.get(TIMEOUT), Integer.valueOf(3000));
        assertEquals(prefs.get(EXCLUSIVE), Boolean.TRUE);
        assertEquals(prefs.get(PROTOCOL), "*"); // neither has it, falls to default
    }

    // === Type conversion ===

    @Test
    void typeConversionFromString() {
        assertEquals(PreferenceProvider.convert(String.class, "hello"), "hello");
        assertEquals(PreferenceProvider.convert(Boolean.class, "true"), Boolean.TRUE);
        assertEquals(PreferenceProvider.convert(Boolean.class, "garbage"), Boolean.FALSE);
        assertEquals(PreferenceProvider.convert(Integer.class, "42"), 42);
        assertEquals(PreferenceProvider.convert(Long.class, "9999999999"), 9999999999L);
        assertEquals((byte[]) PreferenceProvider.convert(byte[].class, "cafe"), new byte[]{(byte) 0xCA, (byte) 0xFE});
    }

    @Test
    void typeConversionAlreadyTyped() {
        // Values already the target type pass through without conversion
        assertEquals(PreferenceProvider.convert(Integer.class, 42), 42);
        assertEquals(PreferenceProvider.convert(Boolean.class, true), true);
        assertEquals(PreferenceProvider.convert(Long.class, 99L), 99L);
        assertEquals(PreferenceProvider.convert(String.class, "hello"), "hello");
        var bytes = new byte[]{1, 2, 3};
        assertSame(PreferenceProvider.convert(byte[].class, bytes), bytes);
    }

    @Test
    void typeConversionStripsWhitespace() {
        assertEquals(PreferenceProvider.convert(Boolean.class, " true "), Boolean.TRUE);
        assertEquals(PreferenceProvider.convert(Integer.class, " 42 "), 42);
        assertEquals(PreferenceProvider.convert(Long.class, " 99 "), 99L);
        assertEquals((byte[]) PreferenceProvider.convert(byte[].class, " cafe "), new byte[]{(byte) 0xCA, (byte) 0xFE});
    }

    @Test
    void typeConversionHexPrefix() {
        // Integer hex
        assertEquals(PreferenceProvider.convert(Integer.class, "0x1A"), 26);
        assertEquals(PreferenceProvider.convert(Integer.class, "0XFF"), 255);
        assertEquals(PreferenceProvider.convert(Integer.class, " 0x10 "), 16); // with whitespace

        // Long hex
        assertEquals(PreferenceProvider.convert(Long.class, "0xDEADBEEF"), 0xDEADBEEFL);
        assertEquals(PreferenceProvider.convert(Long.class, "0X1"), 1L);
    }

    @Test
    void typeConversionRejectsInvalid() {
        assertThrows(NumberFormatException.class, () -> PreferenceProvider.convert(Integer.class, "xyz"));
        assertThrows(IllegalArgumentException.class, () -> PreferenceProvider.convert(Double.class, "3.14"));
    }

    @Test
    void typeConversionRejectsTypeMismatch() {
        // Non-String, non-matching type
        assertThrows(IllegalArgumentException.class, () -> PreferenceProvider.convert(Integer.class, 3.14));
        assertThrows(IllegalArgumentException.class, () -> PreferenceProvider.convert(String.class, 42));
    }

    // === Lazy resolution via withProvider ===

    @Test
    void lazyResolutionCreatesTypedValues() {
        var provider = PreferenceProvider.map(Map.of(
                "reader.protocol", "T=1",
                "reader.exclusive", "true",
                "reader.timeout", "3000"
        ), "cli");
        var prefs = new Preferences().withProvider(provider);

        assertEquals(prefs.get(PROTOCOL), "T=1");
        assertEquals(prefs.get(EXCLUSIVE), Boolean.TRUE);
        assertEquals(prefs.get(TIMEOUT), Integer.valueOf(3000));
    }

    @Test
    void lazyResolutionSkipsInvalidConversion() {
        var provider = PreferenceProvider.map(Map.of(
                "reader.protocol", "T=1",
                "reader.timeout", "notanumber"
        ), "cli");
        var prefs = new Preferences().withProvider(provider);

        assertEquals(prefs.get(PROTOCOL), "T=1");
        // Invalid conversion falls back to default
        assertEquals(prefs.get(TIMEOUT), Integer.valueOf(5000));
        assertTrue(prefs.valueOf(TIMEOUT).isEmpty());
    }

    @Test
    void lazyResolutionRespectsValidators() {
        var bounded = Preference.of("port", Integer.class, 8080, false, p -> p > 0 && p < 65536);

        // Valid
        var prefs = new Preferences().withProvider(PreferenceProvider.map(Map.of("port", "443"), "cli"));
        assertEquals(prefs.get(bounded), Integer.valueOf(443));

        // Invalid - validator rejects, falls back to default
        var prefs2 = new Preferences().withProvider(PreferenceProvider.map(Map.of("port", "99999"), "cli"));
        assertEquals(prefs2.get(bounded), Integer.valueOf(8080));
        assertTrue(prefs2.valueOf(bounded).isEmpty());
    }

    @Test
    void lazyResolutionParameterAndByteArray() {
        var provider = PreferenceProvider.map(Map.of("session.key", "abc", "key.data", "deadbeef"), "file");
        var keyData = Preference.of("key.data", byte[].class, new byte[0], false);
        var prefs = new Preferences().withProvider(provider);

        assertEquals(prefs.valueOf(SESSION_KEY).orElseThrow(), "abc");
        assertEquals(prefs.get(keyData), new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});
    }

    // === Source tracking through orElse chain ===

    @Test
    void sourceTrackedThroughOrElseChain() {
        var cli = PreferenceProvider.map(Map.of("reader.timeout", "3000"), "cli");
        var file = PreferenceProvider.map(Map.of("reader.exclusive", "true"), "file");
        var prefs = new Preferences().withProvider(cli.orElse(file));

        assertEquals(prefs.sourceOf(TIMEOUT).orElseThrow(), "cli");
        assertEquals(prefs.sourceOf(EXCLUSIVE).orElseThrow(), "file");
        assertEquals(prefs.sourceOf(PROTOCOL).orElseThrow(), "default"); // neither has it, falls to default
    }

    // === Preferences.fromEnvironment convenience ===

    @Test
    void fromEnvironmentReturnsLazyPreferences() {
        var prefs = Preferences.fromEnvironment();
        assertNotNull(prefs);
        assertTrue(prefs.isEmpty()); // no explicit values
        // get() still works - returns defaults when env var not set
        assertNotNull(prefs.get(PROTOCOL));
    }
}
