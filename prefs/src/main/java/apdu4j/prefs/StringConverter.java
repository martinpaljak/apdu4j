// SPDX-FileCopyrightText: 2025 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.prefs;

import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Function;

// Bidirectional String <-> V conversion for preferences.
// @FunctionalInterface: parse-only lambdas work directly (e.g. HexBytes::valueOf).
// Built-in types (String, Boolean, Integer, Long, byte[]) auto-derive via forType().
@FunctionalInterface
public interface StringConverter<V> {

    V parse(String s);

    default String format(V value) {
        return String.valueOf(value);
    }

    // Round-trip factory for both directions
    static <V> StringConverter<V> of(Function<String, V> parse, Function<V, String> format) {
        return new StringConverter<>() {
            @Override
            public V parse(String s) {
                return parse.apply(s);
            }

            @Override
            public String format(V v) {
                return format.apply(v);
            }
        };
    }

    // Built-in converters for known types; unknown types throw on parse
    @SuppressWarnings("unchecked")
    static <V> StringConverter<V> forType(Class<V> type) {
        if (type == String.class) {
            return (StringConverter<V>) STRING;
        }
        if (type == Boolean.class) {
            return (StringConverter<V>) BOOLEAN;
        }
        if (type == Integer.class) {
            return (StringConverter<V>) INTEGER;
        }
        if (type == Long.class) {
            return (StringConverter<V>) LONG;
        }
        if (type == byte[].class) {
            return (StringConverter<V>) BYTES;
        }
        return (StringConverter<V>) UNSUPPORTED;
    }

    // Cached stateless singletons

    StringConverter<String> STRING = s -> s.strip();

    StringConverter<Boolean> BOOLEAN = s -> Boolean.parseBoolean(s.strip());

    StringConverter<Integer> INTEGER = s -> {
        s = s.strip();
        if (s.toLowerCase(Locale.ROOT).startsWith("0x")) {
            return Integer.parseInt(s.substring(2), 16);
        }
        return Integer.parseInt(s);
    };

    StringConverter<Long> LONG = s -> {
        s = s.strip();
        if (s.toLowerCase(Locale.ROOT).startsWith("0x")) {
            return Long.parseLong(s.substring(2), 16);
        }
        return Long.parseLong(s);
    };

    StringConverter<byte[]> BYTES = StringConverter.of(
            s -> HexFormat.of().parseHex(s.strip()),
            v -> HexFormat.of().formatHex(v)
    );

    StringConverter<Object> UNSUPPORTED = s -> {
        throw new IllegalArgumentException("No StringConverter registered for this type");
    };
}
