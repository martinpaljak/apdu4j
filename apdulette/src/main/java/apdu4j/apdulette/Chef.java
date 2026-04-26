// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import apdu4j.core.BIBO;
import apdu4j.prefs.Preferences;

/**
 * Executes a {@link Recipe}, driving the prepare-transmit-evaluate loop until
 * a final result (or error) is produced.
 *
 * <p>Chef separates the "what" ({@link Recipe} composition) from the "how"
 * (actual APDU transmission). Different implementations can execute the same
 * recipe against real cards, simulators, or test mocks.
 *
 * <p>Use {@link #cook} when you only need the result value.
 * Use {@link #serve} when you also need the accumulated {@link Preferences}
 * that recipes contributed via {@link Verdict.NextStep}.
 *
 * @see SousChef
 * @see Recipe
 * @see Dish
 */
public interface Chef {

    // Default Chef for a BIBO channel
    static Chef of(BIBO bibo) {
        return new SousChef(bibo);
    }

    /**
     * Executes the recipe and serves the plated result: value plus
     * all preferences accumulated during the recipe chain.
     *
     * @param recipe the recipe to execute
     * @param prefs  initial preferences available to the recipe
     * @param <T>    the result type
     * @return the plated dish with result and accumulated preferences
     * @throws KitchenDisaster if the recipe fails (unhandled card error, iteration limit)
     */
    <T> Dish<T> serve(Recipe<T> recipe, Preferences prefs);

    /**
     * Executes the recipe with empty preferences, returning the plated result.
     *
     * @param recipe the recipe to execute
     * @param <T>    the result type
     * @return the plated dish with result and accumulated preferences
     * @throws KitchenDisaster if the recipe fails (unhandled card error, iteration limit)
     */
    default <T> Dish<T> serve(Recipe<T> recipe) {
        return serve(recipe, new Preferences());
    }

    /**
     * Executes the recipe with the given preferences, returning the final result.
     * Convenience for {@code serve(recipe, prefs).value()}.
     *
     * @param recipe the recipe to execute
     * @param prefs  initial preferences available to the recipe
     * @param <T>    the result type
     * @return the recipe's result
     * @throws KitchenDisaster if the recipe fails (unhandled card error, iteration limit)
     */
    default <T> T cook(Recipe<T> recipe, Preferences prefs) {
        return serve(recipe, prefs).value();
    }

    /**
     * Executes the recipe with empty preferences, returning the final result.
     *
     * @param recipe the recipe to execute
     * @param <T>    the result type
     * @return the recipe's result
     * @throws KitchenDisaster if the recipe fails (unhandled card error, iteration limit)
     */
    default <T> T cook(Recipe<T> recipe) {
        return cook(recipe, new Preferences());
    }

}
