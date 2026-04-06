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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Preferences {
    private static final Logger logger = LoggerFactory.getLogger(Preferences.class);

    // Value paired with its provenance: "env", "prop", "cli", "file", "code", "default", or free-form
    public record Sourced(Object value, String source) {
        public Sourced {
            Objects.requireNonNull(value);
            Objects.requireNonNull(source);
        }
    }

    private final Map<Preference<?>, Sourced> values;
    private final PreferenceProvider provider; // optional lazy fallback

    public Preferences() {
        this(Map.of(), null);
    }

    private Preferences(final Map<Preference<?>, Sourced> values, final PreferenceProvider provider) {
        var sorted = new TreeMap<Preference<?>, Sourced>(Preference.comparator());
        sorted.putAll(values);
        this.values = Collections.unmodifiableSortedMap(sorted);
        this.provider = provider;
    }

    /**
     * Attach a provider for lazy fallback resolution.
     * When {@link #get} or {@link #valueOf} finds no explicit value in the map,
     * the provider is consulted before falling back to the preference default.
     * The provider propagates through {@link #with}, {@link #merge}, and {@link #without}.
     *
     * @param provider the fallback provider (env vars, system properties, etc.)
     * @return a new Preferences with the same explicit values and the given provider
     */
    public Preferences withProvider(final PreferenceProvider provider) {
        return new Preferences(this.values, provider);
    }

    // Explicit set - throws on readonly overwrite
    public <V> Preferences with(final Preference<V> key, final V value) {
        return with(key, value, Source.CODE);
    }

    public <V> Preferences with(final Preference<V> key, final V value, final String source) {
        if (value == null) {
            throw new IllegalArgumentException("Cannot set null outcome for preference '" + key.name() + "'");
        }
        if (key.readonly() && values.containsKey(key)) {
            throw new IllegalStateException("Cannot overwrite readonly preference '" + key.name() + "'");
        }
        if (!key.validator().test(value)) {
            throw new IllegalArgumentException("Value for preference '" + key.name() + "' fails validation: " + value);
        }
        final var newValues = new HashMap<Preference<?>, Sourced>(values);
        newValues.put(key, new Sourced(value, source));
        return new Preferences(newValues, this.provider);
    }

    public <V> Preferences without(final Preference<V> key) {
        if (!values.containsKey(key)) {
            return this;
        }
        if (key.readonly()) {
            throw new IllegalArgumentException("Can't remove readonly preference!");
        }
        final var newValues = new HashMap<Preference<?>, Sourced>(values);
        newValues.remove(key);
        return new Preferences(newValues, this.provider);
    }

    // Always returns non-null: explicit map value, then provider (converted + validated), then default
    @SuppressWarnings("unchecked")
    public <V> V get(final Preference.Default<V> key) {
        final var sourced = values.get(key);
        if (sourced != null) {
            return (V) sourced.value();
        }
        var resolved = resolveFromProvider(key);
        if (resolved.isPresent()) {
            return (V) resolved.get().value();
        }
        return key.defaultValue();
    }

    // Returns explicit map value or provider-resolved value (converted + validated), empty if neither
    @SuppressWarnings("unchecked")
    public <V> Optional<V> valueOf(final Preference<V> key) {
        final var sourced = values.get(key);
        if (sourced != null) {
            return Optional.of((V) sourced.value());
        }
        return resolveFromProvider(key).map(s -> (V) s.value());
    }

    // Source of the effective value. Every value has a source; empty iff no value exists.
    public <V> Optional<String> sourceOf(final Preference<V> key) {
        final var sourced = values.get(key);
        if (sourced != null) {
            return Optional.of(sourced.source());
        }
        var resolved = resolveFromProvider(key);
        if (resolved.isPresent()) {
            return Optional.of(resolved.get().source());
        }
        if (key instanceof Preference.Default<?>) {
            return Optional.of(Source.DEFAULT);
        }
        return Optional.empty();
    }

    // Resolve from provider: convert + validate, or empty
    @SuppressWarnings("unchecked")
    private <V> Optional<Sourced> resolveFromProvider(Preference<V> key) {
        if (provider == null) {
            return Optional.empty();
        }
        var raw = provider.resolve(key);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            var rawValue = raw.get().value();
            V converted;
            if (key.type().isInstance(rawValue)) {
                converted = key.type().cast(rawValue);
            } else if (rawValue instanceof String s) {
                converted = key.converter().parse(s);
            } else {
                throw new IllegalArgumentException("Cannot convert " + rawValue.getClass().getTypeName() + " to " + key.type().getTypeName());
            }
            if (key.validator().test(converted)) {
                return Optional.of(new Sourced(converted, raw.get().source()));
            }
            logger.warn("Preference '{}': value '{}' fails validation", key.name(), converted);
        } catch (RuntimeException e) {
            logger.warn("Preference '{}': cannot convert '{}' to {}", key.name(), raw.get().value(), key.type().getSimpleName());
        }
        return Optional.empty();
    }

    // Bulk merge - silently skips readonly keys that already exist in this instance.
    // This instance's provider survives (the accumulated base carries the provider).
    public Preferences merge(final Preferences other) {
        final var newValues = new HashMap<>(this.values);
        for (var entry : other.values.entrySet()) {
            final Preference<?> key = entry.getKey();
            if (key.readonly() && this.values.containsKey(key)) {
                continue;
            }
            newValues.put(key, entry.getValue());
        }
        return new Preferences(newValues, this.provider);
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
        for (var e : values.entrySet()) {
            sb.append(e.getKey().name());
            sb.append("(");
            sb.append(e.getKey().type().getTypeName());
            sb.append(")=");
            sb.append(formatValue(e.getKey(), e.getValue()));
            sb.append("[");
            sb.append(e.getValue().source());
            sb.append("];");
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static <V> String formatValue(Preference<V> key, Sourced sourced) {
        return key.converter().format((V) sourced.value());
    }

    /**
     * Convenience: create Preferences backed by environment variables.
     * Converts preference name "foo.bar" to env var "FOO_BAR".
     * Values are resolved lazily on {@link #get} and {@link #valueOf}.
     *
     * @return Preferences with environment provider attached
     */
    public static Preferences fromEnvironment() {
        return new Preferences().withProvider(PreferenceProvider.environment());
    }

    public static Preferences of() {
        return new Preferences();
    }

    public static <V> Preferences of(Preference<V> key, V value) {
        return new Preferences().with(key, value);
    }

    public static <V1, V2> Preferences of(Preference<V1> k1, V1 v1, Preference<V2> k2, V2 v2) {
        return new Preferences().with(k1, v1).with(k2, v2);
    }
}
