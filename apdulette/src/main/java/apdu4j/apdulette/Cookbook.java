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
 * var recipe = Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, aid, 256))
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
     * more than a single command (where {@link #send(Function, Taster)} suffices):
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
     * @param taster evaluates the response into a verdict
     * @param <T>    the result type
     * @return a deferred single-command recipe
     */
    public static <T> Recipe<T> send(Function<Preferences, CommandAPDU> cmd, Taster<T> taster) {
        return prefs -> new PreparationStep.Ingredients<>(List.of(cmd.apply(prefs)), List.of(), taster);
    }

    /**
     * Sends a command and checks for {@code 9000}.
     * Produces {@link Verdict.Error} on mismatch - composable with
     * {@link Recipe#orElse} and {@link Recipe#recover}.
     *
     * @param cmd the command to send
     * @return a recipe that produces the response on {@code 9000}
     */
    public static Recipe<ResponseAPDU> send(CommandAPDU cmd) {
        return send(cmd, expect(0x9000));
    }

    /**
     * Sends a command and checks for the expected status word.
     * Produces {@link Verdict.Error} on mismatch - composable with
     * {@link Recipe#orElse} and {@link Recipe#recover}.
     *
     * @param cmd        the command to send
     * @param expectedSW the expected status word (e.g. {@code 0x9000})
     * @return a recipe that produces the response, or fails on SW mismatch
     */
    public static Recipe<ResponseAPDU> send(CommandAPDU cmd, int expectedSW) {
        return send(cmd, expect(expectedSW));
    }

    /**
     * Sends a command and evaluates the response with a custom taster.
     *
     * @param cmd    the command to send
     * @param taster evaluates the response into a verdict
     * @param <T>    the result type
     * @return a recipe that sends the command and applies the taster
     */
    public static <T> Recipe<T> send(CommandAPDU cmd, Taster<T> taster) {
        return prefs -> new PreparationStep.Ingredients<>(List.of(cmd), List.of(), taster);
    }

    /**
     * Sends a command, checks for {@code 9000}, and extracts the response data bytes.
     * The most common pattern for data-retrieval commands.
     *
     * @param cmd the command to send
     * @return a recipe that produces the response data bytes on {@code 9000}
     */
    public static Recipe<byte[]> data(CommandAPDU cmd) {
        return send(cmd).map(ResponseAPDU::getData);
    }

    /**
     * Sends a command, checks for {@code 9000}, extracts data bytes, and transforms them.
     *
     * @param cmd the command to send
     * @param f   transformation applied to the extracted data bytes
     * @param <T> the result type
     * @return a recipe that produces the transformed data on {@code 9000}
     */
    public static <T> Recipe<T> data(CommandAPDU cmd, Function<byte[], T> f) {
        return send(cmd).map(ResponseAPDU::getData).map(f);
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
        return send(cmd).map(ResponseAPDU::getData).filter(test, errorMsg);
    }

    /**
     * GET DATA for card UID via the PC/SC pseudo-APDU ({@code FF CA 00 00}).
     * Validates that the returned data has a valid UID length (4, 7, or 10 bytes).
     *
     * @return a recipe that produces the UID bytes on {@code 9000}
     */
    public static Recipe<byte[]> uid() {
        return data(new CommandAPDU(0xFF, 0xCA, 0x00, 0x00, 256), b -> b.length == 4 || b.length == 7 || b.length == 10, "not a valid UID length");
    }

    // === Taster factories (building blocks for custom recipes) ===

    /**
     * Taster that checks the first response for a specific status word.
     *
     * @param sw the expected status word
     * @return taster - {@link Verdict.Ready} on match, {@link Verdict.Error} on mismatch
     */
    public static Taster<ResponseAPDU> expect(int sw) {
        return Taster.of(r -> r.getSW() == sw, "expected %04X".formatted(sw));
    }

    /**
     * Taster that accepts any of the given status words. Useful for commands
     * that succeed with multiple SWs (e.g. {@code 9000} or {@code 6283}).
     *
     * @param sws the acceptable status words
     * @return taster - {@link Verdict.Ready} on any match, {@link Verdict.Error} on mismatch
     */
    public static Taster<ResponseAPDU> expect(int... sws) {
        return Taster.of(r -> Arrays.stream(sws).anyMatch(sw -> r.getSW() == sw), "expected one of " + Arrays.stream(sws).mapToObj("%04X"::formatted).collect(Collectors.joining(", ")));
    }

    /**
     * Taster that checks for a status word then applies a predicate.
     *
     * @param sw       the expected status word
     * @param test     predicate applied after SW check passes
     * @param errorMsg error message if the predicate fails
     * @return taster - {@link Verdict.Ready} on pass, {@link Verdict.Error} on mismatch or predicate failure
     */
    public static Taster<ResponseAPDU> expect(int sw, Predicate<ResponseAPDU> test, String errorMsg) {
        return expect(sw).refine(test, errorMsg);
    }

    /**
     * Taster that checks the first response with a custom predicate.
     *
     * @param test     predicate applied to the first response
     * @param errorMsg error message if the predicate fails
     * @return taster - {@link Verdict.Ready} on pass, {@link Verdict.Error} on fail
     */
    public static Taster<ResponseAPDU> check(Predicate<ResponseAPDU> test, String errorMsg) {
        return Taster.of(test, errorMsg);
    }

    /**
     * Taster that checks for {@code 9000}, extracts data bytes, and validates
     * them with a predicate. Composes SW check, extraction, and validation.
     *
     * @param test     predicate applied to the extracted data bytes
     * @param errorMsg error message if the predicate fails
     * @return taster that produces validated data bytes
     */
    public static Taster<byte[]> data(Predicate<byte[]> test, String errorMsg) {
        return expect(0x9000).map(ResponseAPDU::getData).refine(test, errorMsg);
    }

    /**
     * Taster that accepts any status word. Use when the caller handles
     * all SW branching in a subsequent {@link Recipe#then} step.
     *
     * @return taster - always {@link Verdict.Ready} with the first response
     */
    public static Taster<ResponseAPDU> any() {
        return Taster.first(Verdict.Ready::new);
    }

    /**
     * Taster that checks every response for the expected status word,
     * returning the last response on success. Multi-response companion to {@link #expect}.
     *
     * @param sw the expected status word
     * @return taster - {@link Verdict.Ready} with last response on all-match,
     * {@link Verdict.Error} on first mismatch
     */
    public static Taster<ResponseAPDU> all(int sw) {
        return Taster.every(r -> r.getSW() == sw, "expected %04X".formatted(sw));
    }

    /**
     * Sends multiple commands in a single step and evaluates all responses
     * with a custom taster. Unlike chaining individual {@link #send(CommandAPDU, int)}
     * recipes with {@link Recipe#and}, this produces one {@link PreparationStep.Ingredients}
     * step with all commands.
     *
     * @param commands the commands to send
     * @param taster   evaluates all responses into a verdict
     * @param <T>      the result type
     * @return a recipe that sends all commands and evaluates responses together
     */
    public static <T> Recipe<T> send(List<CommandAPDU> commands, Taster<T> taster) {
        return prefs -> new PreparationStep.Ingredients<>(List.copyOf(commands), List.of(), taster);
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
        return prefs -> new PreparationStep.Ingredients<>(List.copyOf(commands), expected, all(expectedSW));
    }

    // === Accumulation tasters ===

    /**
     * Taster that checks every response for the expected status word and
     * concatenates all response data. Multi-response companion to {@link #data(CommandAPDU)}.
     *
     * @param sw the expected status word for every response
     * @return taster that produces concatenated data bytes from all responses
     */
    public static Taster<byte[]> allData(int sw) {
        return (responses, prefs) -> {
            var bo = new java.io.ByteArrayOutputStream();
            for (var r : responses) {
                if (r.getSW() != sw) {
                    return new Verdict.Error<>(r, "expected %04X".formatted(sw));
                }
                bo.writeBytes(r.getData());
            }
            return new Verdict.Ready<>(bo.toByteArray());
        };
    }

    // === Accumulation recipes ===

    /**
     * Step decision for {@link #gather}: done, continue, or fail.
     */
    public sealed interface Gather {
        record Done(byte[] data) implements Gather {
        }

        record More(byte[] data, CommandAPDU next) implements Gather {
        }

        record Fail(String error) implements Gather {
        }
    }

    /**
     * Sends a command and accumulates response data across multiple exchanges.
     * The step function examines each response and the accumulated byte count,
     * deciding whether to continue, finish, or fail.
     *
     * <p>Covers SW-continuation (GP GET STATUS 6310, ISO GET RESPONSE 61xx)
     * and offset-based reads (READ BINARY until EOF):
     * <pre>{@code
     * var recipe = Cookbook.gather(
     *     new CommandAPDU(0x00, 0xB0, 0x00, 0x00, 256),
     *     (r, offset) -> {
     *         if (r.getSW() == 0x9000 && r.getData().length == 256) {
     *             int next = offset + r.getData().length;
     *             return new Gather.More(r.getData(),
     *                 new CommandAPDU(0x00, 0xB0, next >> 8, next & 0xFF, 256));
     *         }
     *         return r.getSW1() == 0x90 || r.getSW1() == 0x62
     *             ? new Gather.Done(r.getData())
     *             : new Gather.Fail("READ BINARY failed");
     *     });
     * }</pre>
     *
     * @param cmd  the initial command to send
     * @param step examines each response + accumulated byte count, returns decision
     * @return a recipe that produces the concatenated data from all exchanges
     */
    public static Recipe<byte[]> gather(CommandAPDU cmd, BiFunction<ResponseAPDU, Integer, Gather> step) {
        return gatherLoop(cmd, step, new byte[0]);
    }

    private static Recipe<byte[]> gatherLoop(CommandAPDU cmd, BiFunction<ResponseAPDU, Integer, Gather> step, byte[] acc) {
        return send(cmd, any()).then(r -> switch (step.apply(r, acc.length)) {
            case Gather.Done(var data) -> Recipe.premade(concat(acc, data));
            case Gather.More(var data, var next) -> gatherLoop(next, step, concat(acc, data));
            case Gather.Fail(var msg) -> Recipe.error(msg);
        });
    }

    private static byte[] concat(byte[] a, byte[] b) {
        if (a.length == 0) {
            return b;
        }
        if (b.length == 0) {
            return a;
        }
        var result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
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
        return recipe.then(opt -> opt.isPresent() ? Recipe.premade(opt.get()) : Recipe.error(errorMsg));
    }
}
