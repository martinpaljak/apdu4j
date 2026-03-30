/*
 * Copyright (c) 2026-present Martin Paljak <martin@martinpaljak.net>
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
package apdu4j.apdulette;

import apdu4j.apdulette.PreparationStep.Failed;
import apdu4j.apdulette.PreparationStep.Ingredients;
import apdu4j.apdulette.PreparationStep.Premade;
import apdu4j.apdulette.Verdict.Error;
import apdu4j.apdulette.Verdict.NextStep;
import apdu4j.apdulette.Verdict.Ready;
import apdu4j.prefs.Preferences;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.*;

/**
 * A lazy, composable description of a card interaction that produces a value of type {@code T}.
 *
 * <p>Recipes are pure data - composing them does no I/O. A recipe only becomes
 * real when a {@link Chef} executes it. This separation means you can build,
 * transform, and combine card interaction sequences before touching a card:
 * <pre>{@code
 * var recipe = Cookbook.selectFCI(aid)
 *     .and(Cookbook.raw(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, 256)))
 *     .map(r -> r.getData().length);
 * int len = chef.cook(recipe, prefs);
 * }</pre>
 *
 * <p>Compose with {@link #then}, {@link #map}, {@link #and}. Handle errors
 * with {@link #orElse} and {@link #recover}. Execute with {@link Chef#cook}.
 *
 * @param <T> the result type
 * @see Chef
 * @see Cookbook
 * @see PreparationStep
 */
@FunctionalInterface
public interface Recipe<T> {

    /**
     * Resolves this recipe against the given preferences, producing a pure
     * value ({@link PreparationStep.Premade}), commands to transmit
     * ({@link PreparationStep.Ingredients}), or a known failure
     * ({@link PreparationStep.Failed}).
     *
     * @param prefs typed configuration available to the recipe at prepare-time
     * @return the preparation step - ready value, commands + taster, or failure
     */
    PreparationStep<T> prepare(Preferences prefs);

    /**
     * Lifts a pure value into a recipe. No I/O, no commands - the value is
     * immediately available. Useful for injecting constants or computed values
     * into a recipe chain.
     *
     * @param value the value to wrap (must not be null - use {@link #error} for failures)
     * @param <T>   the value type
     * @return a recipe that always produces {@code value}
     */
    static <T> Recipe<T> premade(T value) {
        Objects.requireNonNull(value, "Use Recipe.error() for failure, not premade(null)");
        return prefs -> new Premade<>(value);
    }

    /**
     * Creates a recipe that always fails with the given reason.
     * The {@link Chef} will throw {@link KitchenDisaster} when executing this.
     * No I/O is performed - the failure is known at prepare-time.
     *
     * @param reason human-readable error description
     * @param <T>    the nominal result type (never actually produced)
     * @return a recipe that always fails
     */
    static <T> Recipe<T> error(String reason) {
        return prefs -> new Failed<>(reason);
    }

    /**
     * Chains a dependent operation on this recipe's result (flatMap). The function
     * receives the result of this recipe and returns a new recipe to execute next.
     *
     * <p>Errors from this step propagate - {@code f} is never called if this recipe
     * produces an {@link Verdict.Error}.
     *
     * @param f   function that takes this recipe's result and returns the next recipe
     * @param <U> result type of the chained recipe
     * @return a composed recipe that runs this, then applies {@code f} to the result
     */
    default <U> Recipe<U> then(Function<T, Recipe<U>> f) {
        return prefs -> switch (prepare(prefs)) {
            case Premade<T>(var v) -> f.apply(v).prepare(prefs);
            case Failed<T>(var reason) -> new Failed<>(reason);
            case Ingredients<T> ing -> new Ingredients<>(ing.commands(), ing.expected(), responses ->
                    switch (ing.taster().apply(responses)) {
                        case Ready<T>(var v, var p) -> new NextStep<>(f.apply(v), p);
                        case NextStep<T>(var r, var p) -> new NextStep<>(r.then(f), p);
                        case Error<T> err -> new Error<>(err.response(), err.message());
                    });
        };
    }

    /**
     * Transforms the result without new I/O. Equivalent to
     * {@code then(t -> premade(f.apply(t)))}.
     *
     * @param f   transformation function
     * @param <U> the new result type
     * @return a recipe that applies {@code f} to this recipe's result
     */
    default <U> Recipe<U> map(Function<T, U> f) {
        return then(t -> premade(f.apply(t)));
    }

    /**
     * Sequences two recipes: runs this, discards its result, runs {@code next}.
     *
     * @param next the recipe to run after this one
     * @param <U>  result type of {@code next}
     * @return a recipe that produces the result of {@code next}
     */
    default <U> Recipe<U> and(Recipe<U> next) {
        return then(ignored -> next);
    }

    /**
     * Falls back to {@code fallback} if this step produces an {@link Verdict.Error}.
     *
     * <p>Scoped to this step only - does not catch errors from downstream
     * {@link #then} continuations. Those propagate as {@link KitchenDisaster}.
     *
     * @param fallback the recipe to try on error
     * @return a recipe with fallback behavior
     */
    default Recipe<T> orElse(Recipe<T> fallback) {
        return prefs -> switch (prepare(prefs)) {
            case Premade<T> p -> p;
            case Failed<T> f -> fallback.prepare(prefs);
            case Ingredients<T> ing -> new Ingredients<>(ing.commands(), ing.expected(), responses -> {
                var verdict = ing.taster().apply(responses);
                return switch (verdict) {
                    case Error<T> e -> new NextStep<>(fallback);
                    default -> verdict;
                };
            });
        };
    }

    /**
     * Recovers from an {@link Verdict.Error} on this step, with access to the
     * error details. Like {@link #orElse} but the handler can inspect the
     * status word to decide how to recover.
     *
     * <p>Scoped to this step only - does not catch downstream errors.
     * Does not catch {@link PreparationStep.Failed} - those propagate as-is
     * because they carry no status word to inspect.
     *
     * @param handler function that receives the error and returns a recovery recipe
     * @return a recipe with error recovery
     */
    default Recipe<T> recover(Function<Error<T>, Recipe<T>> handler) {
        return prefs -> switch (prepare(prefs)) {
            case Premade<T> p -> p;
            case Failed<T> f -> f;
            case Ingredients<T> ing -> new Ingredients<>(ing.commands(), ing.expected(), responses -> {
                var verdict = ing.taster().apply(responses);
                return switch (verdict) {
                    case Error<T> err -> new NextStep<>(handler.apply(err));
                    default -> verdict;
                };
            });
        };
    }

    /**
     * Short-circuits with an exception if the result fails the predicate.
     *
     * @param test predicate to check the result
     * @param ex   exception supplier, thrown when {@code test} returns false
     * @return a recipe that validates its result before continuing
     */
    default Recipe<T> validate(Predicate<T> test, Supplier<? extends RuntimeException> ex) {
        return then(t -> {
            if (!test.test(t)) {
                throw ex.get();
            }
            return premade(t);
        });
    }

    /**
     * Runs a side-effect on the result, then continues with the same value.
     * Useful for logging or capturing intermediate results.
     *
     * @param action the side-effect to perform
     * @return a recipe that runs {@code action} and passes the value through
     */
    default Recipe<T> consume(Consumer<T> action) {
        return then(t -> {
            action.accept(t);
            return premade(t);
        });
    }

    /**
     * Wraps this recipe so card errors produce {@link Optional#empty()} instead of failing.
     * Only catches {@link Verdict.Error} (the card said no), not {@link PreparationStep.Failed}
     * (prepare-time failure). Useful for probing card features without aborting the recipe chain.
     *
     * @return a recipe that produces {@code Optional.of(result)} on success, {@code Optional.empty()} on card error
     */
    default Recipe<Optional<T>> optional() {
        return this.map(Optional::of)
                .recover(err -> premade(Optional.empty()));
    }

    /**
     * Short-circuits with a prepare-time failure if the result fails the predicate.
     * Unlike {@link #validate} which throws a runtime exception, this stays within
     * the recipe error model - the failure is a {@link PreparationStep.Failed} that
     * propagates as a {@link KitchenDisaster} but does not bypass the Chef trampoline.
     *
     * @param test     predicate to check the result
     * @param errorMsg error message when {@code test} returns false
     * @return a recipe that filters its result, failing on predicate mismatch
     */
    default Recipe<T> filter(Predicate<T> test, String errorMsg) {
        return then(v -> test.test(v) ? premade(v) : error(errorMsg));
    }

}
