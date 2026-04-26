// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import apdu4j.core.CommandAPDU;
import apdu4j.core.ResponseAPDU;
import apdu4j.prefs.Preferences;

import java.util.List;
import java.util.Objects;

// What recipe.prepare(prefs) returns: either a pure value, APDUs to send, preferences to inject, or a failure
public sealed interface PreparationStep<T> {
    // Pure value, no I/O needed
    record Premade<T>(T value) implements PreparationStep<T> {
    }

    // Commands to transmit + function to evaluate responses into a verdict
    record Ingredients<T>(List<CommandAPDU> commands, List<ResponseAPDU> expected,
                          Taster<T> taster) implements PreparationStep<T> {
        public Ingredients {
            commands = List.copyOf(commands);
            expected = List.copyOf(expected);
            if (!expected.isEmpty() && expected.size() != commands.size()) {
                throw new IllegalArgumentException("Expected %d responses for %d commands".formatted(expected.size(), commands.size()));
            }
        }
    }

    // Inject preferences into the execution context, then continue with recipe
    record Seasoned<T>(Recipe<T> recipe, Preferences prefs) implements PreparationStep<T> {
        public Seasoned {
            Objects.requireNonNull(recipe);
            Objects.requireNonNull(prefs);
        }
    }

    // Known failure at prepare-time, no I/O needed
    record Failed<T>(String reason) implements PreparationStep<T> {
    }
}
