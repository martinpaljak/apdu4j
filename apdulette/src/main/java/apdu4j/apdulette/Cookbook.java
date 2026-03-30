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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
     * Creates a recipe from a factory that reads {@link Preferences} at prepare-time
     * and returns another recipe. The returned recipe is immediately prepared with
     * the same preferences - eliminating the manual {@code .prepare(prefs)} tail-call
     * that would otherwise be required.
     *
     * <p>Use this when the recipe to build depends on preferences but produces
     * more than a single command (where {@link #simple} suffices):
     * <pre>{@code
     * var recipe = Cookbook.deferred(prefs -> {
     *     var blockSize = prefs.get(BLOCK_SIZE);
     *     var commands = splitIntoBlocks(data, blockSize);
     *     return Cookbook.send(commands, 0x9000);
     * });
     * }</pre>
     *
     * @param factory function that reads preferences and returns a recipe
     * @param <T>     the result type
     * @return a recipe that defers construction to prepare-time
     */
    public static <T> Recipe<T> deferred(Function<Preferences, Recipe<T>> factory) {
        return prefs -> factory.apply(prefs).prepare(prefs);
    }

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
        var expected = List.of(ResponseAPDU.of(expectedSW));
        return prefs -> new PreparationStep.Ingredients<>(List.of(cmd), expected, expect(expectedSW));
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
                    : new Verdict.Error<>(r, "expected %04X".formatted(sw));
        };
    }

    /**
     * Taster that accepts any of the given status words. Returns the first
     * response on match, error on mismatch. Useful for commands like SELECT
     * that succeed with multiple SWs (e.g. {@code 9000} or {@code 6283}).
     *
     * @param sws the acceptable status words
     * @return taster function - {@link Verdict.Ready} on any match, {@link Verdict.Error} on mismatch
     */
    public static Function<List<ResponseAPDU>, Verdict<ResponseAPDU>> expect(int... sws) {
        return responses -> {
            var r = responses.getFirst();
            for (int sw : sws) {
                if (r.getSW() == sw) {
                    return new Verdict.Ready<>(r);
                }
            }
            return new Verdict.Error<>(r, "expected one of " +
                    Arrays.stream(sws).mapToObj("%04X"::formatted).collect(Collectors.joining(", ")));
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
                return new Verdict.Error<>(r, "expected %04X".formatted(sw));
            }
            return test.test(r)
                    ? new Verdict.Ready<>(r)
                    : new Verdict.Error<>(r, errorMsg);
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
                    : new Verdict.Error<>(r, errorMsg);
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
                    : new Verdict.Error<>(r, "expected 9000");
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
                return new Verdict.Error<>(r, "expected 9000");
            }
            return test.test(r.getData())
                    ? new Verdict.Ready<>(r.getData())
                    : new Verdict.Error<>(r, errorMsg);
        };
    }

    /**
     * Taster that checks every response for the expected status word,
     * returning the last response on success. Multi-response companion to {@link #expect}.
     *
     * @param sw the expected status word
     * @return taster function - {@link Verdict.Ready} with last response on all-match,
     * {@link Verdict.Error} on first mismatch
     */
    public static Function<List<ResponseAPDU>, Verdict<ResponseAPDU>> expectAll(int sw) {
        return responses -> {
            for (var r : responses) {
                if (r.getSW() != sw) {
                    return new Verdict.Error<>(r, "expected %04X".formatted(sw));
                }
            }
            return new Verdict.Ready<>(responses.getLast());
        };
    }

    /**
     * Sends multiple commands in a single step and evaluates all responses
     * with a custom taster. Unlike chaining individual {@link #send(CommandAPDU, int)}
     * recipes with {@link Recipe#and}, this produces one {@link PreparationStep.Ingredients}
     * step with all commands.
     *
     * @param commands the commands to send
     * @param taster   function that evaluates all responses into a verdict
     * @param <T>      the result type
     * @return a recipe that sends all commands and evaluates responses together
     */
    public static <T> Recipe<T> send(List<CommandAPDU> commands,
                                     Function<List<ResponseAPDU>, Verdict<T>> taster) {
        return prefs -> new PreparationStep.Ingredients<>(List.copyOf(commands), taster);
    }

    /**
     * Sends multiple commands in a single step, checking each response for the
     * expected status word. Returns the last response on success. Handles empty
     * command lists by returning a synthetic {@code 9000} response.
     *
     * @param commands   the commands to send
     * @param expectedSW the expected status word for every response (e.g. {@code 0x9000})
     * @return a recipe that sends all commands and checks all responses
     */
    public static Recipe<ResponseAPDU> send(List<CommandAPDU> commands, int expectedSW) {
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("commands must not be empty");
        }
        var expected = Collections.nCopies(commands.size(), ResponseAPDU.of(expectedSW));
        return prefs -> new PreparationStep.Ingredients<>(List.copyOf(commands), expected, expectAll(expectedSW));
    }

    // === Recipe combinators ===

    /**
     * Tries each recipe in order, returning the first success. If all fail,
     * the error from the last recipe propagates. Useful for AID or protocol discovery
     * where multiple alternatives may be valid.
     *
     * @param alternatives the recipes to try, in order of preference
     * @param <T>          the result type
     * @return a recipe that produces the result of the first successful alternative
     */
    public static <T> Recipe<T> firstOf(List<Recipe<T>> alternatives) {
        if (alternatives.isEmpty()) {
            return Recipe.error("No alternatives");
        }
        var result = alternatives.getFirst();
        for (int i = 1; i < alternatives.size(); i++) {
            result = result.orElse(alternatives.get(i));
        }
        return result;
    }

    /**
     * Runs all recipes in order and collects their results into a list.
     * If any recipe fails, the entire sequence fails. Results preserve
     * the order of the input list.
     *
     * @param recipes the recipes to execute sequentially
     * @param <T>     the result type of each recipe
     * @return a recipe that produces a list of all results
     */
    public static <T> Recipe<List<T>> sequence(List<Recipe<T>> recipes) {
        if (recipes.isEmpty()) {
            return Recipe.premade(List.of());
        }
        var result = recipes.getFirst().map(v -> {
            var list = new ArrayList<T>();
            list.add(v);
            return list;
        });
        for (int i = 1; i < recipes.size(); i++) {
            var r = recipes.get(i);
            result = result.then(list -> r.map(v -> {
                list.add(v);
                return list;
            }));
        }
        return result.map(List::copyOf);
    }

    /**
     * Applies a function to each item, producing a recipe per item, then
     * runs all recipes sequentially and collects the results.
     * Equivalent to {@code sequence(items.stream().map(f).toList())}.
     *
     * @param items the input items
     * @param f     function that produces a recipe for each item
     * @param <A>   the input type
     * @param <B>   the result type of each recipe
     * @return a recipe that produces a list of all results
     */
    public static <A, B> Recipe<List<B>> traverse(List<A> items, Function<A, Recipe<B>> f) {
        return sequence(items.stream().map(f).toList());
    }

    /**
     * Monadic fold: threads an accumulator through a list of items, applying
     * a step function that returns a recipe for each. Each step receives the
     * accumulated value from all previous steps.
     *
     * @param initial the starting accumulator value
     * @param items   the items to fold over
     * @param step    function taking (accumulator, item) and returning a recipe for the next accumulator
     * @param <A>     the item type
     * @param <B>     the accumulator type
     * @return a recipe that produces the final accumulated value
     */
    public static <A, B> Recipe<B> foldLeft(B initial, List<A> items, BiFunction<B, A, Recipe<B>> step) {
        var result = Recipe.premade(initial);
        for (var item : items) {
            result = result.then(acc -> step.apply(acc, item));
        }
        return result;
    }

    /**
     * Converts a recipe that produces {@link Optional#empty()} into a failure.
     * Inverse of {@link Recipe#optional()} - requires the optional value to be present.
     *
     * @param recipe   the recipe that may produce an empty optional
     * @param errorMsg error message if the optional is empty
     * @param <T>      the value type inside the optional
     * @return a recipe that produces the unwrapped value, or fails if empty
     */
    public static <T> Recipe<T> require(Recipe<Optional<T>> recipe, String errorMsg) {
        return recipe.then(opt -> opt.isPresent()
                ? Recipe.premade(opt.get())
                : Recipe.error(errorMsg));
    }
}
