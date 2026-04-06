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

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Map;

public class PreferencesTest {

    static final Preference.Default<String> NAME = Preference.of("name", String.class, "default", false);
    static final Preference.Default<String> READONLY = Preference.of("readonly", String.class, "readonly_default", true);
    static final Preference.Parameter<String> SESSION = Preference.parameter("session", String.class, true);

    // === Preference type: construction, identity, invariants ===

    @Test
    void preferenceCreation() {
        // Default: accessors
        Assert.assertFalse(NAME.readonly());
        Assert.assertEquals(NAME.defaultValue(), "default");
        Assert.assertEquals(NAME.toString(), "Preference.Default[name]");
        Assert.assertEquals(NAME.type(), String.class);

        // Parameter: accessors
        Assert.assertTrue(SESSION.readonly());
        Assert.assertEquals(SESSION.toString(), "Preference.Parameter[session]");

        // Identity: (name, type) only
        var other = Preference.of("name", String.class, "different_default", true);
        Assert.assertEquals(NAME, other);
        Assert.assertEquals(NAME.hashCode(), other.hashCode());
        Assert.assertNotEquals(NAME, Preference.of("name", Integer.class, 0, false));

        // Rejected: blank names, bad default, null values, validator failure
        Assert.assertThrows(IllegalArgumentException.class, () -> Preference.of("", String.class, "d", false));
        Assert.assertThrows(IllegalArgumentException.class, () -> Preference.of("   ", String.class, "d", false));
        Assert.assertThrows(IllegalArgumentException.class, () -> Preference.parameter("", String.class, false));
        Assert.assertThrows(IllegalArgumentException.class, () ->
                Preference.of("bad", String.class, "x", false, s -> s.length() > 5));
        Assert.assertThrows(IllegalArgumentException.class, () -> new Preferences().with(NAME, null));
        Assert.assertThrows(IllegalArgumentException.class, () -> new Preferences().with(SESSION, null));

        // Validator on with()
        var bounded = Preference.of("port", Integer.class, 8080, false, p -> p > 0 && p < 65536);
        Assert.assertEquals(new Preferences().with(bounded, 443).get(bounded), Integer.valueOf(443));
        Assert.assertThrows(IllegalArgumentException.class, () -> new Preferences().with(bounded, -1));
    }

    // === Resolution without provider ===

    @Test
    void getAndValueOfSemantics() {
        var prefs = new Preferences();

        // Default: get returns default, valueOf empty, sourceOf "default"
        Assert.assertEquals(prefs.get(NAME), "default");
        Assert.assertTrue(prefs.valueOf(NAME).isEmpty());
        Assert.assertEquals(prefs.sourceOf(NAME).orElseThrow(), "default");

        // Parameter: valueOf empty, sourceOf empty
        Assert.assertTrue(prefs.valueOf(SESSION).isEmpty());
        Assert.assertTrue(prefs.sourceOf(SESSION).isEmpty());

        // Explicit value: get/valueOf/sourceOf
        prefs = prefs.with(NAME, "explicit");
        Assert.assertEquals(prefs.get(NAME), "explicit");
        Assert.assertEquals(prefs.valueOf(NAME).orElseThrow(), "explicit");
        Assert.assertEquals(prefs.sourceOf(NAME).orElseThrow(), "code");

        // Explicit source via 3-arg with()
        prefs = prefs.with(NAME, "from_cli", "cli");
        Assert.assertEquals(prefs.get(NAME), "from_cli");
        Assert.assertEquals(prefs.sourceOf(NAME).orElseThrow(), "cli");

        // Parameter with value
        prefs = prefs.with(SESSION, "abc123");
        Assert.assertEquals(prefs.valueOf(SESSION).orElseThrow(), "abc123");
    }

    // === Explicit value manipulation, collection ops, ordering ===

    @Test
    void withWithoutAndKeys() {
        var pref = Preference.of("p", Boolean.class, false, false);
        var prefs = new Preferences().with(pref, true);
        Assert.assertTrue(prefs.get(pref));
        Assert.assertEquals(prefs.size(), 1);

        // without removes, reverts to default
        prefs = prefs.without(pref);
        Assert.assertFalse(prefs.get(pref));
        Assert.assertTrue(prefs.isEmpty());

        // without on non-existent is no-op
        Assert.assertEquals(prefs.without(pref), prefs);

        // Mixed Default + Parameter, keys contains both
        prefs = new Preferences().with(NAME, "overridden").with(SESSION, "key123");
        Assert.assertEquals(prefs.size(), 2);
        Assert.assertTrue(prefs.keys().contains(NAME));
        Assert.assertTrue(prefs.keys().contains(SESSION));

        // Deterministic ordering by name
        var a = Preference.of("aaa", String.class, "d", false);
        var b = Preference.of("bbb", String.class, "d", false);
        var c = Preference.of("ccc", String.class, "d", false);
        var ordered = new Preferences().with(c, "3").with(a, "1").with(b, "2");
        var keys = new ArrayList<>(ordered.keys());
        Assert.assertEquals(keys.get(0).name(), "aaa");
        Assert.assertEquals(keys.get(1).name(), "bbb");
        Assert.assertEquals(keys.get(2).name(), "ccc");
    }

    // === Readonly semantics ===

    @Test
    void readonlyContract() {
        var prefs = new Preferences().with(READONLY, "locked");
        Assert.assertThrows(IllegalStateException.class, () -> prefs.with(READONLY, "new"));
        Assert.assertThrows(IllegalArgumentException.class, () -> prefs.without(READONLY));
    }

    // === Merge ===

    @Test
    void mergeSemantics() {
        var mutable = Preference.of("m", String.class, "d", false);

        // Mutable: later wins, source from winner
        var a = new Preferences().with(mutable, "first", "cli");
        var b = new Preferences().with(mutable, "second", "file");
        var merged = a.merge(b);
        Assert.assertEquals(merged.get(mutable), "second");
        Assert.assertEquals(merged.sourceOf(mutable).orElseThrow(), "file");

        // Readonly: existing survives
        var withReadonly = new Preferences().with(READONLY, "original");
        Assert.assertEquals(withReadonly.merge(new Preferences().with(READONLY, "override")).get(READONLY), "original");

        // Readonly into empty: accepted
        Assert.assertEquals(new Preferences().merge(withReadonly).get(READONLY), "original");
    }

    // === Display formatting ===

    @Test
    void toStringFormatting() {
        Assert.assertEquals(new Preferences().with(NAME, "val").toString(),
                "Preferences{name(java.lang.String)=val[code];}");

        var bytes = Preference.of("key", byte[].class, new byte[0], false);
        Assert.assertEquals(new Preferences().with(bytes, new byte[]{(byte) 0xCA, (byte) 0xFE}).toString(),
                "Preferences{key(byte[])=cafe[code];}");

        Assert.assertEquals(new Preferences().with(NAME, "val", "cli").toString(),
                "Preferences{name(java.lang.String)=val[cli];}");
    }

    // === Provider lifecycle: lazy fallback, precedence, propagation ===

    @Test
    void providerResolution() {
        var provider = PreferenceProvider.map(Map.of("name", "from_provider"), "file");
        var prefs = new Preferences().withProvider(provider);

        // Provider consulted when no explicit value
        Assert.assertEquals(prefs.get(NAME), "from_provider");
        Assert.assertEquals(prefs.valueOf(NAME).orElseThrow(), "from_provider");
        Assert.assertEquals(prefs.sourceOf(NAME).orElseThrow(), "file");

        // Explicit > provider
        prefs = prefs.with(NAME, "explicit");
        Assert.assertEquals(prefs.get(NAME), "explicit");
        Assert.assertEquals(prefs.sourceOf(NAME).orElseThrow(), "code");

        // Provider survives with(), without(), merge()
        var other = Preference.of("other", String.class, "d", false);
        var base = new Preferences().withProvider(provider);
        Assert.assertEquals(base.with(other, "x").get(NAME), "from_provider");
        Assert.assertEquals(base.with(other, "x").without(other).get(NAME), "from_provider");
        var extra = new Preferences().with(Preference.of("extra", String.class, "d", false), "val");
        Assert.assertEquals(base.merge(extra).get(NAME), "from_provider");

        // fromEnvironment convenience
        var envPrefs = Preferences.fromEnvironment();
        Assert.assertTrue(envPrefs.isEmpty());
        Assert.assertNotNull(envPrefs.get(NAME));
    }

    // === Custom converter: parse, format, identity ===

    @Test
    void withConverterParseAndFormat() {
        // Parse-only lambda on Parameter
        var hex = Preference.parameter("count", Integer.class, false)
                .withConverter(s -> Integer.parseInt(s.strip(), 16));
        var provider = PreferenceProvider.map(Map.of("count", "FF"), "test");
        Assert.assertEquals(new Preferences().withProvider(provider).valueOf(hex).orElseThrow(), Integer.valueOf(255));

        // withConverter preserves identity
        Assert.assertEquals(hex, Preference.parameter("count", Integer.class, false));

        // Round-trip converter on Default: custom format reflected in toString
        var upper = Preference.of("key", byte[].class, new byte[0], false)
                .withConverter(StringConverter.of(
                        s -> java.util.HexFormat.of().withUpperCase().parseHex(s.strip()),
                        v -> java.util.HexFormat.of().withUpperCase().formatHex(v)
                ));
        Assert.assertEquals(new Preferences().withProvider(PreferenceProvider.map(Map.of("key", "cafe"), "test"))
                .get(upper), new byte[]{(byte) 0xCA, (byte) 0xFE});
        Assert.assertTrue(new Preferences().with(upper, new byte[]{(byte) 0xCA, (byte) 0xFE}).toString().contains("CAFE"));
    }
}
