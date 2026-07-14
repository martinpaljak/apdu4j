// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import apdu4j.core.BIBO;
import apdu4j.core.BIBOSA;
import apdu4j.prefs.Preferences;

/**
 * Executes a {@link Recipe}, driving the prepare-transmit-evaluate loop to a result.
 *
 * <p>Separates the "what" ({@link Recipe}) from the "how" (APDU transmission): the same
 * recipe runs against real cards, simulators, or mocks.
 *
 * <p>{@link #cook} returns the value; {@link #serve} also returns the {@link Preferences}
 * recipes contributed via {@link Verdict.NextStep}.
 *
 * @see MasterChef
 * @see Recipe
 * @see Dish
 */
public interface Chef {

    // Default Chef for a BIBO channel
    static Chef of(BIBO bibo) {
        return new MasterChef(bibo);
    }

    // BIBOSA variant: stack.preferences() is the baseline context for every recipe.
    static Chef of(BIBOSA stack) {
        return new MasterChef(stack, stack.preferences());
    }

    // Serve with the given baseline preferences. Throws KitchenDisaster on failure.
    <T> Dish<T> serve(Recipe<T> recipe, Preferences prefs);

    default <T> Dish<T> serve(Recipe<T> recipe) {
        return serve(recipe, new Preferences());
    }

    // cook = serve(...).value(): the result without the accumulated preferences.
    default <T> T cook(Recipe<T> recipe, Preferences prefs) {
        return serve(recipe, prefs).value();
    }

    default <T> T cook(Recipe<T> recipe) {
        return cook(recipe, new Preferences());
    }

}
