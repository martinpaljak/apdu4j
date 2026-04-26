// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import apdu4j.core.BIBOSA;
import apdu4j.prefs.Preferences;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

// Chef factory - produces Chefs from transport, never exposing SousChef to consumers.
// Implementations bind to specific transports (PC/SC, simulator, etc).
public interface KitchenManager extends AutoCloseable {

    // A chef bound to a card session with its BIBOSA stack.
    // wrap() creates independent Chefs over wrapped versions of the base stack.
    record ChefSession(Chef chef, BIBOSA stack) {
        public Preferences preferences() {
            return stack.preferences();
        }

        // Wrap the base stack, get a new Chef for the wrapped connection.
        // Always wraps the session's BIBOSA - each call is independent.
        public Chef wrap(Function<BIBOSA, BIBOSA> wrapper) {
            return new SousChef(wrapper.apply(stack));
        }
    }

    // Teppanyaki: dedicated Chef session on a resolved reader.
    // Connects, creates Chef + BIBOSA stack, calls fn, disconnects.
    <T> T teppanyaki(Function<ChefSession, T> fn);

    // KitchenPass: execute recipe on every card that appears.
    // For each tap: create Chef, serve recipe, deliver Dish.
    // Errors per tap go to onError (not propagated).
    // Blocks until close() or interrupt.
    <T> void kitchenPass(Recipe<T> recipe, Consumer<Dish<T>> consumer, BiConsumer<Preferences, Exception> onError);

    @Override
    void close();
}
