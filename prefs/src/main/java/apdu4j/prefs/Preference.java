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

import java.util.Comparator;
import java.util.Objects;
import java.util.function.Predicate;

public sealed interface Preference<V> permits Preference.Default, Preference.Parameter {
    String name();

    Class<V> type();

    boolean readonly();

    Predicate<V> validator();

    StringConverter<V> converter();

    static <T> Default<T> of(String name, Class<T> type, T defaultValue, boolean readonly) {
        return new Default<>(name, type, defaultValue, readonly, x -> true);
    }

    static <T> Default<T> of(String name, Class<T> type, T defaultValue, boolean readonly, Predicate<T> validator) {
        return new Default<>(name, type, defaultValue, readonly, validator);
    }

    static <T> Parameter<T> parameter(String name, Class<T> type, boolean readonly) {
        return new Parameter<>(name, type, readonly, x -> true);
    }

    static <T> Parameter<T> parameter(String name, Class<T> type, boolean readonly, Predicate<T> validator) {
        return new Parameter<>(name, type, readonly, validator);
    }

    // Deterministic ordering by name, then type
    @SuppressWarnings("Convert2MethodRef")
    static Comparator<Preference<?>> comparator() {
        // Lambda required: Preference::name fails inference with wildcard generic
        return Comparator.comparing((Preference<?> p) -> p.name())
                .thenComparing(p -> p.type().getTypeName());
    }

    // Not a record: equals/hashCode on name + type only (excludes validator, converter, metadata)
    abstract class Base<V> {
        private final String name;
        private final Class<V> type;
        private final boolean readonly;
        private final Predicate<V> validator;
        private final StringConverter<V> converter;

        Base(String name, Class<V> type, boolean readonly, Predicate<V> validator) {
            this(name, type, readonly, validator, StringConverter.forType(type));
        }

        Base(String name, Class<V> type, boolean readonly, Predicate<V> validator, StringConverter<V> converter) {
            this.name = Objects.requireNonNull(name);
            if (name.isBlank()) {
                throw new IllegalArgumentException("Preference name must not be blank");
            }
            this.type = Objects.requireNonNull(type);
            this.readonly = readonly;
            this.validator = Objects.requireNonNull(validator, "Must have a validator!");
            this.converter = Objects.requireNonNull(converter);
        }

        public String name() {
            return name;
        }

        public Class<V> type() {
            return type;
        }

        public boolean readonly() {
            return readonly;
        }

        public Predicate<V> validator() {
            return validator;
        }

        public StringConverter<V> converter() {
            return converter;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Preference<?> p && name.equals(p.name()) && type.equals(p.type());
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, type);
        }
    }

    final class Default<V> extends Base<V> implements Preference<V> {
        private final V defaultValue;

        public Default(String name, Class<V> type, V defaultValue, boolean readonly, Predicate<V> validator) {
            super(name, type, readonly, validator);
            this.defaultValue = Objects.requireNonNull(defaultValue, "Must have a sane default value!");
            if (!validator.test(defaultValue)) {
                throw new IllegalArgumentException("Default value for preference '" + name + "' fails validation: " + defaultValue);
            }
        }

        Default(String name, Class<V> type, V defaultValue, boolean readonly, Predicate<V> validator, StringConverter<V> converter) {
            super(name, type, readonly, validator, converter);
            this.defaultValue = Objects.requireNonNull(defaultValue, "Must have a sane default value!");
            if (!validator.test(defaultValue)) {
                throw new IllegalArgumentException("Default value for preference '" + name + "' fails validation: " + defaultValue);
            }
        }

        public V defaultValue() {
            return defaultValue;
        }

        public Default<V> withConverter(StringConverter<V> converter) {
            return new Default<>(name(), type(), defaultValue, readonly(), validator(), converter);
        }

        @Override
        public String toString() {
            return "Preference.Default[" + name() + "]";
        }
    }

    final class Parameter<V> extends Base<V> implements Preference<V> {
        public Parameter(String name, Class<V> type, boolean readonly, Predicate<V> validator) {
            super(name, type, readonly, validator);
        }

        Parameter(String name, Class<V> type, boolean readonly, Predicate<V> validator, StringConverter<V> converter) {
            super(name, type, readonly, validator, converter);
        }

        public Parameter<V> withConverter(StringConverter<V> converter) {
            return new Parameter<>(name(), type(), readonly(), validator(), converter);
        }

        @Override
        public String toString() {
            return "Preference.Parameter[" + name() + "]";
        }
    }
}
