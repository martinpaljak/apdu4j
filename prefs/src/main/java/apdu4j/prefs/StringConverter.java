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
