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
import java.util.function.Function;

/**
 * Resolves preference values from external sources (environment variables,
 * system properties, properties files, or custom sources like user prompts).
 *
 * <p>Providers compose via {@link #orElse(PreferenceProvider)} to form priority
 * chains and attach to {@link Preferences} for lazy resolution:
 * <pre>{@code
 * var provider = PreferenceProvider.environment()
 *     .orElse(PreferenceProvider.systemProperties());
 * var prefs = new Preferences().withProvider(provider);
 * var timeout = prefs.get(Readers.TIMEOUT); // resolved on demand
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
 * @see Preferences#fromEnvironment()
 */
@FunctionalInterface
public interface PreferenceProvider {

    /**
     * Resolves a value for the given preference, or empty if not available.
     * The value may already be the preference's declared type, or a raw string
     * that will be coerced during resolution.
     *
     * @param key the preference to resolve
     * @return the resolved value (typed or raw string), or empty
     */
    Optional<?> resolve(Preference<?> key);

    /**
     * Chains this provider with a fallback. This provider is tried first;
     * if it returns empty, the fallback is consulted.
     *
     * @param fallback the provider to try when this one returns empty
     * @return a composite provider
     */
    default PreferenceProvider orElse(PreferenceProvider fallback) {
        return key -> {
            var result = resolve(key);
            return result.isPresent() ? result : fallback.resolve(key);
        };
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
     * Provider backed by a {@link Map} with preference names as keys.
     * Values may be strings (coerced during resolution) or already typed.
     *
     * @param map the map to read from (keys are preference names)
     * @return map-backed provider
     */
    static PreferenceProvider map(Map<String, ?> map) {
        return key -> Optional.ofNullable(map.get(key.name()));
    }

    /**
     * Provider backed by a typed {@link Map} keyed by {@link Preference} instances.
     * Values should match the preference's declared type; no coercion is needed.
     *
     * @param map the map to read from (keys are Preference instances)
     * @return typed map-backed provider
     */
    static PreferenceProvider typed(Map<Preference<?>, ?> map) {
        return key -> Optional.ofNullable(map.get(key));
    }

    /**
     * Coerces a raw value to the preference's declared type.
     * If the value is already the target type, it is returned as-is.
     * Otherwise, string values are parsed. Supports: String, Boolean, Integer, Long, byte[] (hex).
     *
     * @param type the target type
     * @param raw  the raw value (typed or String)
     * @return the coerced value
     * @throws IllegalArgumentException if the type is unsupported or parsing fails
     */
    static Object coerce(Class<?> type, Object raw) {
        if (type.isInstance(raw)) {
            return raw;
        }
        if (raw instanceof String s) {
            s = s.strip();
            if (type == String.class) {
                return s;
            }
            if (type == Boolean.class) {
                return Boolean.parseBoolean(s);
            }
            if (type == Integer.class) {
                if (s.toLowerCase(Locale.ROOT).startsWith("0x")) {
                    return Integer.parseInt(s.substring(2), 16);
                }
                return Integer.parseInt(s);
            }
            if (type == Long.class) {
                if (s.toLowerCase(Locale.ROOT).startsWith("0x")) {
                    return Long.parseLong(s.substring(2), 16);
                }
                return Long.parseLong(s);
            }
            if (type == byte[].class) {
                return HexFormat.of().parseHex(s);
            }
            throw new IllegalArgumentException("Cannot coerce String to " + type.getTypeName());
        }
        throw new IllegalArgumentException("Cannot coerce " + raw.getClass().getTypeName() + " to " + type.getTypeName());
    }
}
