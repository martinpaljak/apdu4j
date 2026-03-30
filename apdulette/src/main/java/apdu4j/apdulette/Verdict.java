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
