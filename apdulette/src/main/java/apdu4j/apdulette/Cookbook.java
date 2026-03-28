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
import apdu4j.prefs.Preferences;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Static factory methods for common {@link Recipe recipes} and taster functions.
 *
 * <p>Recipes built here are the building blocks you compose into larger card
 * interaction sequences. Taster functions evaluate card responses into
 * {@link Verdict verdicts} that drive the execution loop.
 *
 * <pre>{@code
 * var recipe = Cookbook.selectFCI(aid)
 *     .and(Cookbook.uid());
 * byte[] uid = chef.cook(recipe, prefs);
 * }</pre>
 *
 * @see Recipe
 * @see Verdict
 */
public final class Cookbook {
    private Cookbook() {
    }

    // === Recipe factories ===

    /**
     * Creates a recipe that builds a single command from {@link Preferences} at
     * prepare-time and evaluates the response with the given taster.
     *
     * @param cmd    function that builds the command from preferences
     * @param taster function that evaluates the response list into a verdict
     * @param <T>    the result type
     * @return a deferred single-command recipe
     */
    public static <T> Recipe<T> simple(Function<Preferences, CommandAPDU> cmd,
                                       Function<List<ResponseAPDU>, Verdict<T>> taster) {
        return prefs -> new PreparationStep.Ingredients<>(List.of(cmd.apply(prefs)), taster);
    }

    /**
     * Sends a single command and returns the {@link ResponseAPDU} as-is, no SW check.
     *
     * @param cmd the command to send
     * @return a recipe that produces the raw response
     */
    public static Recipe<ResponseAPDU> raw(CommandAPDU cmd) {
        return prefs -> new PreparationStep.Ingredients<>(
                List.of(cmd),
                responses -> new Verdict.Ready<>(responses.getFirst()));
    }

    /**
     * Sends raw bytes and returns the raw response bytes, no SW check.
     *
     * @param bytes the command bytes to send
     * @return a recipe that produces the raw response bytes
     */
    public static Recipe<byte[]> raw(byte[] bytes) {
        return raw(new CommandAPDU(bytes)).map(ResponseAPDU::getBytes);
    }

    /**
     * Sends a command and checks for the expected status word.
     *
     * @param cmd        the command to send
     * @param expectedSW the expected status word (e.g. {@code 0x9000})
     * @return a recipe that produces the response, or fails on SW mismatch
     */
    public static Recipe<ResponseAPDU> send(CommandAPDU cmd, int expectedSW) {
        return prefs -> new PreparationStep.Ingredients<>(List.of(cmd), expect(expectedSW));
    }

    /**
     * Sends a case 1 command (no data, no Le) and checks for {@code 9000}.
     *
     * @param cla class byte
     * @param ins instruction byte
     * @param p1  parameter 1
     * @param p2  parameter 2
     * @return a recipe that produces the response on {@code 9000}
     */
    public static Recipe<ResponseAPDU> send(int cla, int ins, int p1, int p2) {
        return simple(prefs -> new CommandAPDU(cla, ins, p1, p2), expect(0x9000));
    }

    /**
     * SELECT by AID with FCI response, checks for {@code 9000}.
     * The AID array is defensively copied - safe to reuse after calling.
     *
     * @param aid the application identifier
     * @return a recipe that produces the SELECT response with FCI data
     */
    public static Recipe<ResponseAPDU> selectFCI(byte[] aid) {
        byte[] a = aid.clone(); // defensive: construction is deferred past recipe creation
        return simple(prefs -> new CommandAPDU(0x00, 0xA4, 0x04, 0x00, a, 256), expect(0x9000));
    }

    /**
     * Sends a command, checks for {@code 9000}, and extracts the response data bytes.
     * The most common pattern for data-retrieval commands.
     *
     * @param cmd the command to send
     * @return a recipe that produces the response data bytes on {@code 9000}
     */
    public static Recipe<byte[]> data(CommandAPDU cmd) {
        return simple(prefs -> cmd, data());
    }

    /**
     * Sends a command, checks for {@code 9000}, extracts data bytes, and validates
     * them with a predicate. Combines the common check-and-extract pattern with
     * application-level validation in a single recipe.
     *
     * @param cmd      the command to send
     * @param test     predicate applied to the extracted data bytes
     * @param errorMsg error message if the predicate fails
     * @return a recipe that produces the validated data bytes
     */
    public static Recipe<byte[]> data(CommandAPDU cmd, Predicate<byte[]> test, String errorMsg) {
        return simple(prefs -> cmd, data(test, errorMsg));
    }

    /**
     * GET DATA for card UID via the PC/SC pseudo-APDU ({@code FF CA 00 00}).
     * Validates that the returned data has a valid UID length (4, 7, or 10 bytes).
     *
     * @return a recipe that produces the UID bytes on {@code 9000}
     */
    public static Recipe<byte[]> uid() {
        return data(new CommandAPDU(0xFF, 0xCA, 0x00, 0x00, 256),
                b -> b.length == 4 || b.length == 7 || b.length == 10,
                "not a valid UID length");
    }

    // === Taster factories (building blocks for custom recipes) ===

    /**
     * Taster that checks the first response for a specific status word.
     *
     * @param sw the expected status word
     * @return taster function - {@link Verdict.Ready} on match, {@link Verdict.Error} on mismatch
     */
    public static Function<List<ResponseAPDU>, Verdict<ResponseAPDU>> expect(int sw) {
        return responses -> {
            var r = responses.getFirst();
            return r.getSW() == sw
                    ? new Verdict.Ready<>(r)
                    : new Verdict.Error<>("expected %04X".formatted(sw), r.getSW());
        };
    }

    /**
     * Convenience alias for {@link #expect(int) expect(0x9000)}.
     *
     * @return taster function that checks for {@code 9000}
     */
    public static Function<List<ResponseAPDU>, Verdict<ResponseAPDU>> expect_9000() {
        return expect(0x9000);
    }

    /**
     * Taster that checks the first response for a specific status word and then
     * applies a predicate. Useful when validation depends on both the SW and the
     * response payload (e.g. checking that a SELECT response contains a specific FCI tag).
     *
     * @param sw       the expected status word
     * @param test     predicate applied to the first response after SW check passes
     * @param errorMsg error message if the predicate fails
     * @return taster function - {@link Verdict.Ready} on pass, {@link Verdict.Error} on mismatch or predicate failure
     */
    public static Function<List<ResponseAPDU>, Verdict<ResponseAPDU>> expect(
            int sw, Predicate<ResponseAPDU> test, String errorMsg) {
        return responses -> {
            var r = responses.getFirst();
            if (r.getSW() != sw) {
                return new Verdict.Error<>("expected %04X".formatted(sw), r.getSW());
            }
            return test.test(r)
                    ? new Verdict.Ready<>(r)
                    : new Verdict.Error<>(errorMsg, r.getSW());
        };
    }

    /**
     * Taster that checks the first response with a custom predicate.
     *
     * @param test     predicate applied to the first response
     * @param errorMsg error message if the predicate fails
     * @return taster function - {@link Verdict.Ready} on pass, {@link Verdict.Error} on fail
     */
    public static Function<List<ResponseAPDU>, Verdict<ResponseAPDU>> check(
            Predicate<ResponseAPDU> test, String errorMsg) {
        return responses -> {
            var r = responses.getFirst();
            return test.test(r)
                    ? new Verdict.Ready<>(r)
                    : new Verdict.Error<>(errorMsg, r.getSW());
        };
    }

    /**
     * Taster that extracts the data bytes from the first response on {@code 9000}.
     *
     * @return taster function that produces the response data bytes, or error on SW mismatch
     */
    public static Function<List<ResponseAPDU>, Verdict<byte[]>> data() {
        return responses -> {
            var r = responses.getFirst();
            return r.getSW() == 0x9000
                    ? new Verdict.Ready<>(r.getData())
                    : new Verdict.Error<>("expected 9000", r.getSW());
        };
    }

    /**
     * Taster that extracts the data bytes from the first response on {@code 9000}
     * and validates them with a predicate. Combines SW check, data extraction,
     * and application-level validation in a single taster.
     *
     * @param test     predicate applied to the extracted data bytes
     * @param errorMsg error message if the predicate fails
     * @return taster function that produces validated data bytes, or error on SW mismatch or predicate failure
     */
    public static Function<List<ResponseAPDU>, Verdict<byte[]>> data(
            Predicate<byte[]> test, String errorMsg) {
        return responses -> {
            var r = responses.getFirst();
            if (r.getSW() != 0x9000) {
                return new Verdict.Error<>("expected 9000", r.getSW());
            }
            return test.test(r.getData())
                    ? new Verdict.Ready<>(r.getData())
                    : new Verdict.Error<>(errorMsg, r.getSW());
        };
    }
}
