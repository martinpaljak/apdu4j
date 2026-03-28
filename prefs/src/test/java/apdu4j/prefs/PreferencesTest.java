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

public class PreferencesTest {

    static final Preference.Default<String> NAME = Preference.of("name", String.class, "default", false);
    static final Preference.Default<String> READONLY = Preference.of("readonly", String.class, "readonly_default", true);
    static final Preference.Parameter<String> SESSION = Preference.parameter("session", String.class, true);

    // === Preference creation and identity ===

    @Test
    void preferenceCreation() {
        // Default: has default value, not readonly
        Assert.assertFalse(NAME.readonly());
        Assert.assertEquals(NAME.defaultValue(), "default");
        Assert.assertEquals(NAME.toString(), "Preference.Default[name]");

        // Parameter: no default, readonly
        Assert.assertTrue(SESSION.readonly());
        Assert.assertEquals(SESSION.toString(), "Preference.Parameter[session]");

        // Identity is (name, type) - two instances with same name+type are equal
        var other = Preference.of("name", String.class, "different_default", true);
        Assert.assertEquals(NAME, other);
        Assert.assertEquals(NAME.hashCode(), other.hashCode());

        // Different type, same name - NOT equal
        var intPref = Preference.of("name", Integer.class, 0, false);
        Assert.assertNotEquals(NAME, intPref);
    }

    @Test
    void preferenceRejectsInvalidConstruction() {
        // Blank names
        Assert.assertThrows(IllegalArgumentException.class, () -> Preference.of("", String.class, "d", false));
        Assert.assertThrows(IllegalArgumentException.class, () -> Preference.of("   ", String.class, "d", false));
        Assert.assertThrows(IllegalArgumentException.class, () -> Preference.parameter("", String.class, false));

        // Default value must pass its own validator
        Assert.assertThrows(IllegalArgumentException.class, () ->
                Preference.of("bad", String.class, "x", false, s -> s.length() > 5));
    }

    // === Preferences: get, valueOf, with, without ===

    @Test
    void getAndValueOfSemantics() {
        var prefs = new Preferences();

        // get() returns default for unset Default preferences
        Assert.assertEquals(prefs.get(NAME), "default");
        // valueOf() returns empty for unset preferences
        Assert.assertTrue(prefs.valueOf(NAME).isEmpty());

        // After setting, both return the explicit value
        prefs = prefs.with(NAME, "explicit");
        Assert.assertEquals(prefs.get(NAME), "explicit");
        Assert.assertEquals(prefs.valueOf(NAME).orElseThrow(), "explicit");
    }

    @Test
    void parametersHaveNoDefault() {
        var prefs = new Preferences();
        Assert.assertTrue(prefs.valueOf(SESSION).isEmpty());

        prefs = prefs.with(SESSION, "abc123");
        Assert.assertEquals(prefs.valueOf(SESSION).orElseThrow(), "abc123");
        // Note: prefs.get(SESSION) does not compile - Parameter has no default
    }

    @Test
    void withAndWithout() {
        var pref = Preference.of("p", Boolean.class, false, false);
        var prefs = new Preferences().with(pref, true);

        Assert.assertTrue(prefs.get(pref));
        Assert.assertEquals(prefs.size(), 1);

        // without() removes, reverts to default
        prefs = prefs.without(pref);
        Assert.assertFalse(prefs.get(pref));
        Assert.assertTrue(prefs.isEmpty());

        // without() on non-existent key is a no-op (returns same instance)
        var same = prefs.without(pref);
        Assert.assertEquals(prefs, same);
    }

    @Test
    void mixedDefaultsAndParameters() {
        var prefs = new Preferences()
                .with(NAME, "overridden")
                .with(SESSION, "key123");

        Assert.assertEquals(prefs.get(NAME), "overridden");
        Assert.assertEquals(prefs.valueOf(SESSION).orElseThrow(), "key123");
        Assert.assertEquals(prefs.size(), 2);
        Assert.assertTrue(prefs.keys().contains(NAME));
        Assert.assertTrue(prefs.keys().contains(SESSION));
    }

    // === Readonly contract ===

    @Test
    void readonlyPreventsOverwriteAndRemoval() {
        var prefs = new Preferences().with(READONLY, "locked");

        // with() on existing readonly throws
        Assert.assertThrows(IllegalStateException.class, () -> prefs.with(READONLY, "new"));
        // without() on readonly throws
        Assert.assertThrows(IllegalArgumentException.class, () -> prefs.without(READONLY));
    }

    // === Merge semantics ===

    @Test
    void mergeSemantics() {
        var mutable = Preference.of("m", String.class, "d", false);

        // Non-readonly: later value wins
        var a = new Preferences().with(mutable, "first");
        var b = new Preferences().with(mutable, "second");
        Assert.assertEquals(a.merge(b).get(mutable), "second");

        // Readonly: existing value survives merge
        var withReadonly = new Preferences().with(READONLY, "original");
        var override = new Preferences().with(READONLY, "override");
        Assert.assertEquals(withReadonly.merge(override).get(READONLY), "original");

        // Readonly into empty: accepted (no conflict)
        Assert.assertEquals(new Preferences().merge(withReadonly).get(READONLY), "original");
    }

    // === Validation ===

    @Test
    void validationEnforced() {
        var bounded = Preference.of("port", Integer.class, 8080, false, p -> p > 0 && p < 65536);

        // Valid value accepted
        var prefs = new Preferences().with(bounded, 443);
        Assert.assertEquals(prefs.get(bounded), Integer.valueOf(443));

        // Invalid value rejected
        Assert.assertThrows(IllegalArgumentException.class, () -> new Preferences().with(bounded, -1));
    }

    // === Invalid inputs ===

    @Test
    void nullValuesRejected() {
        Assert.assertThrows(IllegalArgumentException.class, () -> new Preferences().with(NAME, null));
        Assert.assertThrows(IllegalArgumentException.class, () -> new Preferences().with(SESSION, null));
    }

    // === toString ===

    @Test
    void toStringFormatting() {
        Assert.assertEquals(new Preferences().with(NAME, "val").toString(),
                "Preferences{name(java.lang.String)=val;}");

        var bytes = Preference.of("key", byte[].class, new byte[0], false);
        Assert.assertEquals(new Preferences().with(bytes, new byte[]{(byte) 0xCA, (byte) 0xFE}).toString(),
                "Preferences{key(byte[])=cafe;}");
    }
}
