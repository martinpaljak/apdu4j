// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import apdu4j.core.ResponseAPDU;
import apdu4j.prefs.Preferences;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A function that examines card responses and produces a {@link Verdict}.
 *
 * <p>Tasters are the evaluation building blocks of the recipe system. Compose
 * them with {@link #map} and {@link #refine} instead of writing one-off lambdas:
 * <pre>{@code
 * // Check SW, extract data, validate length - all composed from primitives
 * Taster<byte[]> validated = Taster.of(r -> r.getSW() == 0x9000, "expected 9000")
 *     .map(ResponseAPDU::getData)
 *     .refine(b -> b.length > 0, "empty response");
 * }</pre>
 *
 * @param <T> the result type on success
 * @see Cookbook
 * @see Verdict
 */
@FunctionalInterface
public interface Taster<T> {

    Verdict<T> taste(List<ResponseAPDU> responses, Preferences prefs);

    // Lift a single-response evaluator into a taster
    static <T> Taster<T> first(Function<ResponseAPDU, Verdict<T>> f) {
        return (responses, prefs) -> f.apply(responses.getFirst());
    }

    // Predicate on first response: Ready on pass, Error on fail
    static Taster<ResponseAPDU> of(Predicate<ResponseAPDU> test, String errorMsg) {
        return first(r -> test.test(r) ? new Verdict.Ready<>(r) : new Verdict.Error<>(r, errorMsg));
    }

    // Predicate on first response with dynamic error message from the actual response
    static Taster<ResponseAPDU> of(Predicate<ResponseAPDU> test, Function<ResponseAPDU, String> complainer) {
        return first(r -> test.test(r) ? new Verdict.Ready<>(r) : new Verdict.Error<>(r, complainer.apply(r)));
    }

    // Check all responses, return last on success
    static Taster<ResponseAPDU> every(Predicate<ResponseAPDU> test, String errorMsg) {
        return (responses, prefs) -> {
            for (var r : responses) {
                if (!test.test(r)) {
                    return new Verdict.Error<>(r, errorMsg);
                }
            }
            return new Verdict.Ready<>(responses.getLast());
        };
    }

    // Check all responses with dynamic error message, return last on success
    static Taster<ResponseAPDU> every(Predicate<ResponseAPDU> test, Function<ResponseAPDU, String> complainer) {
        return (responses, prefs) -> {
            for (var r : responses) {
                if (!test.test(r)) {
                    return new Verdict.Error<>(r, complainer.apply(r));
                }
            }
            return new Verdict.Ready<>(responses.getLast());
        };
    }

    // Further test on the Ready value; failures blame the last response,
    // the one the value derives from in multi-response tasters
    default Taster<T> refine(Predicate<T> test, String errorMsg) {
        return (responses, prefs) -> switch (taste(responses, prefs)) {
            case Verdict.Ready<T>(var v, var p) ->
                    test.test(v) ? new Verdict.Ready<>(v, p) : new Verdict.Error<>(responses.getLast(), errorMsg);
            case Verdict.NextStep<T>(var r, var p) -> new Verdict.NextStep<>(r.then(v -> test.test(v) ? Recipe.premade(v) : Recipe.cardError(responses.getLast(), errorMsg)), p);
            case Verdict.Error<T> e -> e;
        };
    }

    // Transform the Ready value
    default <U> Taster<U> map(Function<T, U> f) {
        return (responses, prefs) -> switch (taste(responses, prefs)) {
            case Verdict.Ready<T>(var v, var p) -> new Verdict.Ready<>(f.apply(v), p);
            case Verdict.NextStep<T>(var r, var p) -> new Verdict.NextStep<>(r.map(f), p);
            case Verdict.Error<T> e -> new Verdict.Error<>(e.response(), e.message());
        };
    }

    // Transform Ready value, catching exceptions as Error.
    // Use instead of map() when f parses card data that may be malformed.
    default <U> Taster<U> tryMap(Function<T, U> f) {
        return (responses, prefs) -> switch (taste(responses, prefs)) {
            case Verdict.Ready<T>(var v, var p) -> {
                try {
                    yield new Verdict.Ready<>(f.apply(v), p);
                } catch (RuntimeException e) {
                    yield new Verdict.Error<>(responses.getLast(), message(e));
                }
            }
            case Verdict.NextStep<T>(var r, var p) -> new Verdict.NextStep<>(r.then(v -> {
                try {
                    return Recipe.premade(f.apply(v));
                } catch (RuntimeException e) {
                    return Recipe.cardError(responses.getLast(), message(e));
                }
            }), p);
            case Verdict.Error<T> e -> new Verdict.Error<>(e.response(), e.message());
        };
    }

    // Error text for a caught exception; toString covers messageless ones like NPE
    private static String message(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }
}
