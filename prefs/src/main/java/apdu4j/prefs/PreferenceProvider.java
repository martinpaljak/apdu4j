// SPDX-FileCopyrightText: 2025 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
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
 * var prefs = Preferences.from(provider);
 * var timeout = prefs.get(Readers.TIMEOUT); // resolved on demand
 * }</pre>
 *
 * <p>Custom providers (including interactive user prompts) implement the single
 * {@link #resolve(Preference)} method:
 * <pre>{@code
 * PreferenceProvider askUser = key -> {
 *     System.out.print(key.name() + "? ");
 *     return Optional.of(new Preferences.Sourced(scanner.nextLine(), "user"));
 * };
 * }</pre>
 *
 * @see Preferences#fromEnvironment()
 */
@FunctionalInterface
public interface PreferenceProvider {

    /**
     * Resolves a value for the given preference, or empty if not available.
     * The returned {@link Preferences.Sourced} pairs the value with its provenance
     * (e.g. "env", "prop", "file", or a custom source string).
     *
     * @param key the preference to resolve
     * @return the resolved value with source, or empty
     */
    Optional<Preferences.Sourced> resolve(Preference<?> key);

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
            var envName = key.name().toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
            return Optional.ofNullable(env.apply(envName)).map(v -> new Preferences.Sourced(v, Source.ENV));
        };
    }

    /**
     * Provider that reads Java system properties.
     * Uses the preference name directly as the property key.
     *
     * @return system properties provider
     */
    static PreferenceProvider systemProperties() {
        return key -> Optional.ofNullable(System.getProperty(key.name())).map(v -> new Preferences.Sourced(v, Source.PROP));
    }

    /**
     * Provider backed by a {@link Properties} instance.
     * Uses the preference name as the property key.
     *
     * @param props the properties to read from
     * @return properties-backed provider
     */
    static PreferenceProvider properties(Properties props) {
        return key -> Optional.ofNullable(props.getProperty(key.name())).map(v -> new Preferences.Sourced(v, Source.FILE));
    }

    /**
     * Provider backed by a {@link Map} with preference names as keys.
     * Values may be strings (converted during resolution) or already typed.
     *
     * @param map    the map to read from (keys are preference names)
     * @param source provenance label for resolved values (e.g. "cli", "file")
     * @return map-backed provider
     */
    static PreferenceProvider map(Map<String, ?> map, String source) {
        return key -> Optional.ofNullable(map.get(key.name())).map(v -> new Preferences.Sourced(v, source));
    }

    /**
     * Provider backed by a typed {@link Map} keyed by {@link Preference} instances.
     * Values should match the preference's declared type; no conversion is needed.
     *
     * @param map    the map to read from (keys are Preference instances)
     * @param source provenance label for resolved values
     * @return typed map-backed provider
     */
    static PreferenceProvider typed(Map<Preference<?>, ?> map, String source) {
        return key -> Optional.ofNullable(map.get(key)).map(v -> new Preferences.Sourced(v, source));
    }

}
