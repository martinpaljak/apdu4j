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

import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;

/**
 * Resolves preference values from external sources (environment variables,
 * system properties, properties files, or custom sources like user prompts).
 *
 * <p>Providers compose via {@link #orElse(PreferenceProvider)} to form priority
 * chains, and produce immutable {@link Preferences} snapshots via {@link #resolve}:
 * <pre>{@code
 * var provider = PreferenceProvider.environment()
 *     .orElse(PreferenceProvider.systemProperties());
 * var prefs = provider.resolve(Readers.PROTOCOL, Readers.EXCLUSIVE);
 * }</pre>
 *
 * <p>Custom providers (including interactive user prompts) implement the single
 * {@link #resolve(Preference)} method:
 * <pre>{@code
 * PreferenceProvider askUser = key -> {
 *     System.out.print(key.name() + "? ");
 *     return Optional.of(scanner.nextLine());
 * };
 * }</pre>
 *
 * @see Preferences#fromEnvironment(Preference[])
 */
@FunctionalInterface
public interface PreferenceProvider {

    /**
     * Resolves a raw string value for the given preference, or empty if not available.
     *
     * @param key the preference to resolve
     * @return the raw string value, or empty
     */
    Optional<String> resolve(Preference<?> key);

    /**
     * Chains this provider with a fallback. This provider is tried first;
     * if it returns empty, the fallback is consulted.
     *
     * @param fallback the provider to try when this one returns empty
     * @return a composite provider
     */
    default PreferenceProvider orElse(PreferenceProvider fallback) {
        return key -> resolve(key).or(() -> fallback.resolve(key));
    }

    /**
     * Resolves the given preferences into an immutable snapshot.
     * Values are coerced from strings to the preference's declared type.
     * Preferences whose values fail validation are silently skipped.
     *
     * @param keys the preferences to resolve
     * @return an immutable Preferences containing resolved values
     */
    default Preferences resolve(Preference<?>... keys) {
        return resolve(List.of(keys));
    }

    /**
     * Resolves the given preferences into an immutable snapshot.
     *
     * @param keys the preferences to resolve
     * @return an immutable Preferences containing resolved values
     */
    default Preferences resolve(List<Preference<?>> keys) {
        var result = new Preferences();
        for (var key : keys) {
            var raw = resolve(key);
            if (raw.isPresent()) {
                result = trySet(result, key, raw.get());
            }
        }
        return result;
    }

    // Captures the wildcard into a named type variable so with() type-checks
    @SuppressWarnings("unchecked")
    private static <V> Preferences trySet(Preferences prefs, Preference<V> key, String raw) {
        try {
            var value = (V) coerce(key.type(), raw);
            return prefs.with(key, value);
        } catch (IllegalArgumentException ignored) {
            // Validation failed or type coercion failed - skip this preference
            return prefs;
        }
    }

    /**
     * Provider that reads environment variables.
     * Converts preference name {@code foo.bar} to env var {@code FOO_BAR}.
     *
     * @return environment variable provider
     */
    static PreferenceProvider environment() {
        return environment(System::getenv);
    }

    // Testable: accepts a custom env lookup function
    static PreferenceProvider environment(Function<String, String> env) {
        return key -> {
            var envName = key.name().toUpperCase().replace('.', '_').replace('-', '_');
            return Optional.ofNullable(env.apply(envName));
        };
    }

    /**
     * Provider that reads Java system properties.
     * Uses the preference name directly as the property key.
     *
     * @return system properties provider
     */
    static PreferenceProvider systemProperties() {
        return key -> Optional.ofNullable(System.getProperty(key.name()));
    }

    /**
     * Provider backed by a {@link Properties} instance.
     * Uses the preference name as the property key.
     *
     * @param props the properties to read from
     * @return properties-backed provider
     */
    static PreferenceProvider properties(Properties props) {
        return key -> Optional.ofNullable(props.getProperty(key.name()));
    }

    /**
     * Provider backed by a {@link Map}.
     *
     * @param map the map to read from (keys are preference names)
     * @return map-backed provider
     */
    static PreferenceProvider map(Map<String, String> map) {
        return key -> Optional.ofNullable(map.get(key.name()));
    }

    /**
     * Coerces a raw string to the preference's declared type.
     * Supports: String, Boolean, Integer, Long, byte[] (hex).
     *
     * @param type the target type
     * @param raw  the raw string value
     * @return the coerced value
     * @throws IllegalArgumentException if the type is unsupported or parsing fails
     */
    static Object coerce(Type type, String raw) {
        if (type == String.class) {
            return raw;
        }
        if (type == Boolean.class) {
            return Boolean.parseBoolean(raw);
        }
        if (type == Integer.class) {
            return Integer.parseInt(raw);
        }
        if (type == Long.class) {
            return Long.parseLong(raw);
        }
        if (type == byte[].class) {
            return HexFormat.of().parseHex(raw);
        }
        throw new IllegalArgumentException("Cannot coerce String to " + type.getTypeName());
    }
}
