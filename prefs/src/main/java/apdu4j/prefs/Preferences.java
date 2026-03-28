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

import java.util.*;

public final class Preferences {
    private final Map<Preference<?>, Object> values;

    public Preferences() {
        this(Map.of());
    }

    private Preferences(final Map<Preference<?>, Object> values) {
        var sorted = new TreeMap<Preference<?>, Object>(Preference.comparator());
        sorted.putAll(values);
        this.values = Collections.unmodifiableSortedMap(sorted);
    }

    // Explicit set - throws on readonly overwrite
    public <V> Preferences with(final Preference<V> key, final V value) {
        if (value == null) {
            throw new IllegalArgumentException("Cannot set null outcome for preference '" + key.name() + "'");
        }
        if (key.readonly() && values.containsKey(key)) {
            throw new IllegalStateException("Cannot overwrite readonly preference '" + key.name() + "'");
        }
        if (!key.validator().test(value)) {
            throw new IllegalArgumentException("Value for preference '" + key.name() + "' fails validation: " + value);
        }
        final var newValues = new HashMap<Preference<?>, Object>(values);
        newValues.put(key, value);
        return new Preferences(newValues);
    }

    public <V> Preferences without(final Preference<V> key) {
        if (key.readonly()) {
            throw new IllegalArgumentException("Can't remove readonly preference!");
        }
        if (!values.containsKey(key)) {
            return this;
        }
        final var newValues = new HashMap<Preference<?>, Object>(values);
        newValues.remove(key);
        return new Preferences(newValues);
    }

    // Always returns non-null: either the explicit override or the default outcome
    @SuppressWarnings("unchecked")
    public <V> V get(final Preference.Default<V> key) {
        final var value = (V) values.get(key);
        return value != null ? value : key.defaultValue();
    }

    // Returns empty Optional when using default, present Optional when explicitly overridden
    @SuppressWarnings("unchecked")
    public <V> Optional<V> valueOf(final Preference<V> key) {
        // We don't allow null values, so the optional is empty only
        // if the preference is not explicitly established
        return Optional.ofNullable((V) values.get(key));
    }

    // Bulk merge - silently skips readonly keys that already exist in this instance
    public Preferences merge(final Preferences other) {
        final var newValues = new HashMap<Preference<?>, Object>(this.values);
        for (Map.Entry<Preference<?>, Object> entry : other.values.entrySet()) {
            final Preference<?> key = entry.getKey();
            if (key.readonly() && this.values.containsKey(key)) {
                continue;
            }
            newValues.put(key, entry.getValue());
        }
        return new Preferences(newValues);
    }

    public Set<Preference<?>> keys() {
        return values.keySet();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
    }

    @Override
    public String toString() {
        final var sb = new StringBuilder();
        sb.append("Preferences{");
        for (var k : values.entrySet()) {
            sb.append(k.getKey().name());
            sb.append("(");
            sb.append(k.getKey().type().getTypeName());
            sb.append(")");
            sb.append("=");
            if (k.getValue() instanceof byte[] bytes) {
                sb.append(HexFormat.of().formatHex(bytes));
            } else {
                sb.append(k.getValue());
            }
            sb.append(";");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Convenience: resolve preferences from environment variables.
     * Converts preference name "foo.bar" to env var "FOO_BAR".
     *
     * @param keys the preferences to resolve from environment
     * @return an immutable Preferences with resolved values
     */
    public static Preferences fromEnvironment(Preference<?>... keys) {
        return PreferenceProvider.environment().resolve(keys);
    }

    /**
     * Convenience: resolve preferences from environment variables.
     *
     * @param keys the preferences to resolve from environment
     * @return an immutable Preferences with resolved values
     */
    public static Preferences fromEnvironment(List<Preference<?>> keys) {
        return PreferenceProvider.environment().resolve(keys);
    }
}
