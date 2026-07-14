// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import apdu4j.apdulette.PreparationStep.CardError;
import apdu4j.apdulette.PreparationStep.Ingredients;
import apdu4j.apdulette.PreparationStep.Premade;
import apdu4j.apdulette.PreparationStep.Seasoned;
import apdu4j.apdulette.Verdict.Error;
import apdu4j.apdulette.Verdict.NextStep;
import apdu4j.apdulette.Verdict.Ready;
import apdu4j.core.ResponseAPDU;
import apdu4j.prefs.Preferences;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A lazy, composable description of a card interaction that produces a value of type {@code T}.
 *
 * <p>Recipes are pure data - composing them does no I/O. A recipe only becomes
 * real when a {@link Chef} executes it. This separation allows building,
 * transforming, and combining card interaction sequences before touching a card:
 * <pre>{@code
 * var recipe = Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00))
 *     .and(Cookbook.send(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, 256)))
 *     .map(r -> r.getData().length);
 * int len = chef.cook(recipe, prefs);
 * }</pre>
 *
 * <p>Compose with {@link #then}, {@link #map}, {@link #and}. Execute with {@link Chef#cook}.
 *
 * <p>Failures come in two tiers. Card-tier failures ({@link Verdict.Error}) carry the
 * blamed response and are the only tier caught by {@link #orElse}, {@link #recover}
 * and {@link #optional}. Programmer-tier failures (from {@link #fail}) throw
 * {@link KitchenDisaster} when the recipe is prepared and are caught by no combinator.
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
     * ({@link PreparationStep.Ingredients}), preferences to inject
     * ({@link PreparationStep.Seasoned}), or a card-tier failure
     * ({@link PreparationStep.CardError}). Throws {@link KitchenDisaster} for
     * programmer-tier failures.
     *
     * @param prefs typed configuration available to the recipe at prepare-time
     * @return the preparation step - ready value, commands + taster, preferences + continuation, or card error
     */
    PreparationStep<T> prepare(Preferences prefs);

    /**
     * Lifts a pure value into a recipe. No I/O, no commands - the value is
     * immediately available. Useful for injecting constants or computed values
     * into a recipe chain.
     *
     * @param value the value to wrap (must not be null - use {@link #fail} for failures)
     * @param <T>   the value type
     * @return a recipe that always produces {@code value}
     */
    static <T> Recipe<T> premade(T value) {
        Objects.requireNonNull(value, "Use Recipe.fail() for failure, not premade(null)");
        return prefs -> new Premade<>(value);
    }

    /**
     * Programmer or configuration failure. Throws {@link KitchenDisaster} when
     * the recipe is prepared, caught by no combinator. No I/O is performed.
     * For a recoverable card-tier failure use {@link #cardError}.
     *
     * @param reason human-readable error description
     * @param <T>    the nominal result type (never actually produced)
     * @return a recipe that always fails
     */
    static <T> Recipe<T> fail(String reason) {
        return prefs -> {
            throw new KitchenDisaster(reason);
        };
    }

    /**
     * Programmer or configuration failure with a cause. Throws
     * {@link KitchenDisaster} with {@code cause} attached when the recipe is
     * prepared, caught by no combinator. No I/O is performed.
     *
     * @param reason human-readable error description
     * @param cause  the underlying throwable
     * @param <T>    the nominal result type (never actually produced)
     * @return a recipe that always fails
     */
    static <T> Recipe<T> fail(String reason, Throwable cause) {
        return prefs -> {
            throw new KitchenDisaster(reason, cause);
        };
    }

    /**
     * Card-tier failure: yields {@link Verdict.Error} carrying the blamed response,
     * without transmitting anything. Caught by {@link #orElse}, {@link #recover},
     * {@link #optional}.
     *
     * @param response the response to blame
     * @param message  human-readable error description
     * @param <T>      the nominal result type (never actually produced)
     * @return a recipe that always yields a card error
     */
    static <T> Recipe<T> cardError(ResponseAPDU response, String message) {
        return prefs -> new CardError<>(response, message);
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
            case CardError<T>(var r, var m) -> new CardError<U>(r, m);
            case Seasoned<T>(var r, var p) -> new Seasoned<>(r.then(f), p);
            case Ingredients<T> ing ->
                    new Ingredients<>(ing.commands(), ing.expected(), (responses, tp) -> switch (ing.taster().taste(responses, tp)) {
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
     * Falls back to {@code fallback} if this recipe - including all its
     * downstream {@link #then} continuations - produces an {@link Verdict.Error}.
     * Equivalent to {@code recover(err -> fallback)}.
     *
     * <p>Recovery follows the receiver: to cover a single step, attach the
     * fallback to that step before chaining. Programmer-tier failures
     * (thrown {@link KitchenDisaster}) propagate.
     *
     * @param fallback the recipe to try on error
     * @return a recipe with fallback behavior
     */
    default Recipe<T> orElse(Recipe<T> fallback) {
        return recover(err -> fallback);
    }

    /**
     * Recovers from an {@link Verdict.Error} produced by this recipe -
     * including all its downstream {@link #then} continuations - with access
     * to the error details. Like {@link #orElse} but the handler can inspect
     * the blamed response and status word to decide how to recover.
     *
     * <p>Recovery follows the receiver: to cover a single step, attach the
     * handler to that step before chaining. The handler runs even if earlier
     * steps already transmitted commands - when partial I/O matters (e.g. a
     * half-open secure channel), scope recovery to the failing step.
     * Programmer-tier failures (thrown {@link KitchenDisaster}) propagate.
     *
     * @param handler function that receives the error and returns a recovery recipe
     * @return a recipe with error recovery
     */
    default Recipe<T> recover(Function<Error<T>, Recipe<T>> handler) {
        return prefs -> switch (prepare(prefs)) {
            case Premade<T> p -> p;
            case CardError<T>(var response, var message) -> handler.apply(new Error<>(response, message)).prepare(prefs);
            case Seasoned<T>(var r, var p) -> new Seasoned<>(r.recover(handler), p);
            case Ingredients<T> ing -> new Ingredients<>(ing.commands(), ing.expected(),
                    (responses, p) -> switch (ing.taster().taste(responses, p)) {
                        case Error<T> e -> new NextStep<>(handler.apply(e));
                        case NextStep<T>(var r, var p2) -> new NextStep<>(r.recover(handler), p2);
                        case Ready<T> ready -> ready;
                    });
        };
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
     * Wraps this recipe so card errors anywhere in the chain produce
     * {@link Optional#empty()} instead of failing. Only catches
     * {@link Verdict.Error} (the card said no); programmer-tier failures
     * (thrown {@link KitchenDisaster}) propagate. Useful for probing card
     * features without aborting the recipe chain.
     *
     * @return a recipe that produces {@code Optional.of(result)} on success, {@code Optional.empty()} on card error
     */
    default Recipe<Optional<T>> optional() {
        return this.map(Optional::of).orElse(premade(Optional.empty()));
    }

}
