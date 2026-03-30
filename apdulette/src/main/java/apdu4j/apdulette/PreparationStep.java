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

import apdu4j.core.CommandAPDU;
import apdu4j.core.ResponseAPDU;

import java.util.List;
import java.util.function.Function;

// What recipe.prepare(prefs) returns: either a pure value or APDUs to send
public sealed interface PreparationStep<T> {
    // Pure value, no I/O needed
    record Premade<T>(T value) implements PreparationStep<T> {
    }

    // Commands to transmit + function to evaluate responses into a verdict
    record Ingredients<T>(
            List<CommandAPDU> commands,
            List<ResponseAPDU> expected,
            Function<List<ResponseAPDU>, Verdict<T>> taster
    ) implements PreparationStep<T> {
        // Backward-compatible: no expectations
        public Ingredients(List<CommandAPDU> commands,
                           Function<List<ResponseAPDU>, Verdict<T>> taster) {
            this(commands, List.of(), taster);
        }

        public Ingredients {
            commands = List.copyOf(commands);
            expected = List.copyOf(expected);
            if (!expected.isEmpty() && expected.size() != commands.size()) {
                throw new IllegalArgumentException(
                        "Expected %d responses for %d commands"
                                .formatted(expected.size(), commands.size()));
            }
        }
    }

    // Known failure at prepare-time, no I/O needed
    record Failed<T>(String reason) implements PreparationStep<T> {
    }
}
