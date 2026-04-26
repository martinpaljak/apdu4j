// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import apdu4j.core.ResponseAPDU;
import apdu4j.prefs.Preferences;

// What the taster decides after examining responses
public sealed interface Verdict<T> {
    // Done, here's the result, optionally enriching preferences
    record Ready<T>(T value, Preferences prefs) implements Verdict<T> {
        public Ready(T value) {
            this(value, new Preferences());
        }
    }

    // Continue with a new recipe, optionally enriching preferences
    record NextStep<T>(Recipe<T> recipe, Preferences prefs) implements Verdict<T> {
        public NextStep(Recipe<T> recipe) {
            this(recipe, new Preferences());
        }
    }

    // Error - carry the actual response for diagnostics and recovery
    record Error<T>(ResponseAPDU response, String message) implements Verdict<T> {
        public int sw() {
            return response.getSW();
        }
    }
}
