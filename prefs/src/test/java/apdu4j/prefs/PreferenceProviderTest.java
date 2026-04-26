// SPDX-FileCopyrightText: 2025 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
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

    // === Provider factories: env, sysprop, properties, map ===

    @Test
    void builtInProviders() {
        // Environment: dots/hyphens -> underscores, uppercased
        var env = Map.of("READER_PROTOCOL", "T=1", "MY_SETTING", "value");
        var envProvider = PreferenceProvider.environment(env::get);
        var sourced = envProvider.resolve(PROTOCOL).orElseThrow();
        assertEquals(sourced.value(), "T=1");
        assertEquals(sourced.source(), "env");
        assertEquals(envProvider.resolve(Preference.of("my-setting", String.class, "d", false)).orElseThrow().value(), "value");
        assertTrue(envProvider.resolve(TIMEOUT).isEmpty());

        // System properties
        System.setProperty("reader.protocol", "T=0");
        try {
            var sp = PreferenceProvider.systemProperties().resolve(PROTOCOL).orElseThrow();
            assertEquals(sp.value(), "T=0");
            assertEquals(sp.source(), "prop");
        } finally {
            System.clearProperty("reader.protocol");
        }

        // Properties
        var props = new Properties();
        props.setProperty("reader.protocol", "T=CL");
        var ps = PreferenceProvider.properties(props).resolve(PROTOCOL).orElseThrow();
        assertEquals(ps.value(), "T=CL");
        assertEquals(ps.source(), "file");

        // Map
        var map = PreferenceProvider.map(Map.of("reader.protocol", "DIRECT"), "cli");
        assertEquals(map.resolve(PROTOCOL).orElseThrow().value(), "DIRECT");
        assertEquals(map.resolve(PROTOCOL).orElseThrow().source(), "cli");
        assertTrue(map.resolve(TIMEOUT).isEmpty());
    }

    // === Pre-typed values: typed map, mixed map, passthrough ===

    @Test
    void typedAndMixedProviders() {
        // Typed map: already correct types
        var typed = Map.<Preference<?>, Object>of(PROTOCOL, "T=1", EXCLUSIVE, true, TIMEOUT, 3000);
        var prefs = Preferences.from(PreferenceProvider.typed(typed, "session"));
        assertEquals(prefs.get(PROTOCOL), "T=1");
        assertEquals(prefs.get(EXCLUSIVE), Boolean.TRUE);
        assertEquals(prefs.get(TIMEOUT), Integer.valueOf(3000));

        // Typed missing: falls to default
        var partial = Preferences.from(PreferenceProvider.typed(Map.<Preference<?>, Object>of(PROTOCOL, "T=1"), "session"));
        assertEquals(partial.get(TIMEOUT), Integer.valueOf(5000));
        assertTrue(partial.valueOf(TIMEOUT).isEmpty());

        // Map<String, ?> with pre-typed Integer: isInstance passthrough
        var mixed = Preferences.from(PreferenceProvider.map(Map.of("reader.protocol", "T=1", "reader.timeout", 3000), "cli"));
        assertEquals(mixed.get(PROTOCOL), "T=1");
        assertEquals(mixed.get(TIMEOUT), Integer.valueOf(3000));

        // Unknown type passes through when already typed (no converter needed)
        var dbl = Preference.of("thing", Double.class, 3.14, false);
        assertEquals(Preferences.from(PreferenceProvider.typed(Map.of(dbl, 2.71), "test")).get(dbl), 2.71);
    }

    // === orElse composition: priority, fallback, source tracking ===

    @Test
    void orElseChaining() {
        var primary = PreferenceProvider.map(Map.of("reader.protocol", "T=1"), "cli");
        var fallback = PreferenceProvider.map(Map.of("reader.protocol", "T=0", "reader.timeout", "3000"), "file");
        var chained = primary.orElse(fallback);

        // Primary wins
        assertEquals(chained.resolve(PROTOCOL).orElseThrow().value(), "T=1");
        assertEquals(chained.resolve(PROTOCOL).orElseThrow().source(), "cli");

        // Fallback used when primary empty
        assertEquals(chained.resolve(TIMEOUT).orElseThrow().value(), "3000");
        assertEquals(chained.resolve(TIMEOUT).orElseThrow().source(), "file");

        // Both empty
        assertTrue(chained.resolve(EXCLUSIVE).isEmpty());

        // Triple chain: a.orElse(b).orElse(c)
        var third = PreferenceProvider.map(Map.of("reader.exclusive", "true"), "env");
        var triple = primary.orElse(fallback).orElse(third);
        assertEquals(triple.resolve(PROTOCOL).orElseThrow().source(), "cli");
        assertEquals(triple.resolve(TIMEOUT).orElseThrow().source(), "file");
        assertEquals(triple.resolve(EXCLUSIVE).orElseThrow().source(), "env");

        // Chain with type conversion through Preferences
        var cli = PreferenceProvider.map(Map.of("reader.timeout", "3000"), "cli");
        var file = PreferenceProvider.map(Map.of("reader.exclusive", "true"), "file");
        var prefs = Preferences.from(cli.orElse(file));
        assertEquals(prefs.get(TIMEOUT), Integer.valueOf(3000));
        assertEquals(prefs.get(EXCLUSIVE), Boolean.TRUE);
        assertEquals(prefs.get(PROTOCOL), "*"); // falls to default

        // Source tracked through chain
        assertEquals(prefs.sourceOf(TIMEOUT).orElseThrow(), "cli");
        assertEquals(prefs.sourceOf(EXCLUSIVE).orElseThrow(), "file");
        assertEquals(prefs.sourceOf(PROTOCOL).orElseThrow(), "default");
    }

    // === String -> typed conversion: all built-in types, hex, whitespace ===

    @Test
    void providerTypeConversion() {
        // All 5 built-in types from string
        var provider = PreferenceProvider.map(Map.of(
                "reader.protocol", "T=1", "reader.exclusive", "true", "reader.timeout", "3000"
        ), "cli");
        var prefs = Preferences.from(provider);
        assertEquals(prefs.get(PROTOCOL), "T=1");
        assertEquals(prefs.get(EXCLUSIVE), Boolean.TRUE);
        assertEquals(prefs.get(TIMEOUT), Integer.valueOf(3000));

        // Boolean.parseBoolean: non-"true" strings silently become false
        var boolProv = PreferenceProvider.map(Map.of("reader.exclusive", "yes"), "test");
        assertFalse(Preferences.from(boolProv).get(EXCLUSIVE));

        // Parameter + byte[]
        var keyData = Preference.of("key.data", byte[].class, new byte[0], false);
        var p2 = PreferenceProvider.map(Map.of("session.key", "abc", "key.data", "deadbeef"), "file");
        var prefs2 = Preferences.from(p2);
        assertEquals(prefs2.valueOf(SESSION_KEY).orElseThrow(), "abc");
        assertEquals(prefs2.sourceOf(SESSION_KEY).orElseThrow(), "file");
        assertEquals(prefs2.get(keyData), new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});

        // Hex prefix + Long + whitespace stripping
        var hexInt = Preference.of("hi", Integer.class, 0, false);
        var hexLong = Preference.of("hl", Long.class, 0L, false);
        var p3 = PreferenceProvider.map(Map.of("hi", " 0xFF ", "hl", "0xDEADBEEF"), "test");
        var prefs3 = Preferences.from(p3);
        assertEquals(prefs3.get(hexInt), Integer.valueOf(255));
        assertEquals(prefs3.get(hexLong), Long.valueOf(0xDEADBEEFL));
    }

    // === Error paths: bad conversion, unknown type, validator rejection ===

    @Test
    void providerFailuresAndValidation() {
        // Bad string -> falls to default
        var prefs = Preferences.from(PreferenceProvider.map(Map.of(
                "reader.protocol", "T=1", "reader.timeout", "notanumber"), "cli"));
        assertEquals(prefs.get(PROTOCOL), "T=1");
        assertEquals(prefs.get(TIMEOUT), Integer.valueOf(5000));
        assertTrue(prefs.valueOf(TIMEOUT).isEmpty());

        // Unknown type from string -> falls to default
        var dbl = Preference.of("thing", Double.class, 3.14, false);
        var prefs2 = Preferences.from(PreferenceProvider.map(Map.of("thing", "2.71"), "test"));
        assertEquals(prefs2.get(dbl), 3.14);
        assertTrue(prefs2.valueOf(dbl).isEmpty());

        // Validator: valid value accepted
        var bounded = Preference.of("port", Integer.class, 8080, false, p -> p > 0 && p < 65536);
        assertEquals(Preferences.from(PreferenceProvider.map(Map.of("port", "443"), "cli"))
                .get(bounded), Integer.valueOf(443));

        // Validator: invalid value rejected -> default, sourceOf "default"
        var prefs3 = Preferences.from(PreferenceProvider.map(Map.of("port", "99999"), "cli"));
        assertEquals(prefs3.get(bounded), Integer.valueOf(8080));
        assertTrue(prefs3.valueOf(bounded).isEmpty());
        assertEquals(prefs3.sourceOf(bounded).orElseThrow(), "default");

        // Conversion failure on Parameter (no default fallback)
        var param = Preference.parameter("count", Integer.class, false);
        var badParam = Preferences.from(PreferenceProvider.map(Map.of("count", "notanumber"), "cli"));
        assertTrue(badParam.valueOf(param).isEmpty());
        assertTrue(badParam.sourceOf(param).isEmpty());
    }

    // === StringConverter direct edge cases ===

    @Test
    void stringConverterEdgeCases() {
        // Negative integer and long
        assertEquals(StringConverter.INTEGER.parse("-5"), Integer.valueOf(-5));
        assertEquals(StringConverter.LONG.parse("-100"), Long.valueOf(-100));

        // 0x prefix case-insensitive (uppercase X)
        assertEquals(StringConverter.INTEGER.parse("0XFF"), Integer.valueOf(255));
        assertEquals(StringConverter.LONG.parse("0XFF"), Long.valueOf(255));

        // Boolean: "false", "FALSE", junk all become false; only "true"/"TRUE" -> true
        assertFalse(StringConverter.BOOLEAN.parse("false"));
        assertFalse(StringConverter.BOOLEAN.parse("FALSE"));
        assertFalse(StringConverter.BOOLEAN.parse("yes"));
        assertTrue(StringConverter.BOOLEAN.parse("TRUE"));

        // String strips whitespace
        assertEquals(StringConverter.STRING.parse("  hello  "), "hello");

        // UNSUPPORTED throws
        assertThrows(IllegalArgumentException.class, () -> StringConverter.UNSUPPORTED.parse("anything"));

        // Empty hex prefix: "0x" alone
        assertThrows(NumberFormatException.class, () -> StringConverter.INTEGER.parse("0x"));
        assertThrows(NumberFormatException.class, () -> StringConverter.LONG.parse("0x"));

        // Odd-length hex for bytes
        assertThrows(IllegalArgumentException.class, () -> StringConverter.BYTES.parse("abc"));

        // Empty string
        assertThrows(NumberFormatException.class, () -> StringConverter.INTEGER.parse(""));
        assertThrows(NumberFormatException.class, () -> StringConverter.LONG.parse(""));

        // Integer overflow
        assertThrows(NumberFormatException.class, () -> StringConverter.INTEGER.parse("2147483648"));
    }
}
