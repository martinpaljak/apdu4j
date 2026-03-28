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

import apdu4j.prefs.Preferences;

import java.util.List;

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

    /**
     * Executes the recipe and serves the plated result: value plus
     * all preferences accumulated during the recipe chain.
     *
     * @param recipe the recipe to execute
     * @param prefs  initial preferences available to the recipe
     * @param <T>    the result type
     * @return the plated dish with result and accumulated preferences
     * @throws SpoiledIngredient if the card returns an unexpected status word
     * @throws KitchenDisaster   if execution fails (transport error, iteration limit)
     */
    <T> Dish<T> serve(Recipe<T> recipe, Preferences prefs);

    /**
     * Executes the recipe with empty preferences, returning the plated result.
     *
     * @param recipe the recipe to execute
     * @param <T>    the result type
     * @return the plated dish with result and accumulated preferences
     * @throws SpoiledIngredient if the card returns an unexpected status word
     * @throws KitchenDisaster   if execution fails (transport error, iteration limit)
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
     * @throws SpoiledIngredient if the card returns an unexpected status word
     * @throws KitchenDisaster   if execution fails (transport error, iteration limit)
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
     * @throws SpoiledIngredient if the card returns an unexpected status word
     * @throws KitchenDisaster   if execution fails (transport error, iteration limit)
     */
    default <T> T cook(Recipe<T> recipe) {
        return cook(recipe, new Preferences());
    }

    /**
     * Executes a list of recipes serially, threading preferences through
     * each step. Each recipe sees the accumulated preferences from all
     * previous recipes. Individual result values are discarded.
     *
     * @param recipes the recipes to execute in order
     * @param prefs   initial preferences
     * @return the accumulated preferences after all recipes have executed
     * @throws SpoiledIngredient if any recipe fails
     * @throws KitchenDisaster   if execution fails
     */
    default Preferences runAll(List<Recipe<?>> recipes, Preferences prefs) {
        var current = prefs;
        for (var r : recipes) {
            current = current.merge(serve(r, current).preferences());
        }
        return current;
    }
}
