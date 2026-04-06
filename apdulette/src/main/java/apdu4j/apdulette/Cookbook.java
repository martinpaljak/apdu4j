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

    // === Preference injection ===

    /**
     * Creates a recipe that injects preferences into the execution context and
     * produces the given value. The {@link Chef} trampoline merges the preferences
     * before continuing with downstream recipes.
     *
     * <p>Use inside {@link Recipe#then} when mid-recipe code discovers a value
     * that downstream steps should see as a preference:
     * <pre>{@code
     * Cookbook.send(initUpdate).then(response -> {
     *     var keys = computeKeys(response.getData());
     *     return Cookbook.season(keys, Preferences.of(SCP02_I, response.getData()[11]))
     *         .then(k -> Cookbook.send(buildExtAuth(k)));
     * })
     * }</pre>
     *
     * @param value the value to produce
     * @param prefs the preferences to inject
     * @param <T>   the value type
     * @return a recipe that injects preferences and produces the value
     * @see Recipe#season(Function)
     */
    public static <T> Recipe<T> season(T value, Preferences prefs) {
        return p -> new PreparationStep.Seasoned<>(Recipe.premade(value), prefs);
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
        return send(cmd).map(ResponseAPDU::getData).then(v -> test.test(v) ? Recipe.premade(v) : Recipe.error(errorMsg));
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
     * Taster that checks the first response for a specific status word,
     * using a complainer to produce domain-specific error messages
     * from the actual response.
     *
     * @param sw         the expected status word
     * @param complainer produces the error description from the failing response
     * @return taster - {@link Verdict.Ready} on match, {@link Verdict.Error} on mismatch
     */
    public static Taster<ResponseAPDU> expect(int sw, Function<ResponseAPDU, String> complainer) {
        return Taster.of(r -> r.getSW() == sw, complainer);
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
     * Taster that checks every response for the expected status word,
     * using a complainer for domain-specific error messages.
     * Multi-response companion to {@link #expect(int, Function)}.
     *
     * @param sw         the expected status word
     * @param complainer produces the error description from the failing response
     * @return taster - {@link Verdict.Ready} with last response on all-match,
     * {@link Verdict.Error} on first mismatch
     */
    public static Taster<ResponseAPDU> all(int sw, Function<ResponseAPDU, String> complainer) {
        return Taster.every(r -> r.getSW() == sw, complainer);
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

    // === Looping ===

    /**
     * Loop decision: keep going with new state, or stop with a result.
     *
     * @param <S> the loop state type
     * @param <A> the result type when done
     * @see #loop
     */
    sealed interface Loop<S, A> {
        record Continue<S, A>(S state) implements Loop<S, A> {}
        record Done<S, A>(A result) implements Loop<S, A> {}
    }

    /**
     * General-purpose monadic loop (tailRecM). Repeatedly applies the step
     * function to the current state until it returns {@link Loop.Done}.
     *
     * <p>Each iteration is trampolined through the {@link Chef} - no stack
     * overflow as long as the step produces at least one I/O step per iteration.
     *
     * <pre>{@code
     * // Count card responses until SW != 9000
     * Recipe<Integer> countOk = Cookbook.loop(0, n ->
     *     Cookbook.send(cmd, Cookbook.any()).then(r ->
     *         r.getSW() == 0x9000
     *             ? Recipe.premade(new Loop.Continue<>(n + 1))
     *             : Recipe.premade(new Loop.Done<>(n))));
     * }</pre>
     *
     * @param seed  the initial state
     * @param step  produces a recipe that decides to continue or stop
     * @param <S>   the loop state type
     * @param <A>   the result type
     * @return a recipe that loops until the step returns {@link Loop.Done}
     */
    public static <S, A> Recipe<A> loop(S seed, Function<S, Recipe<Loop<S, A>>> step) {
        return step.apply(seed).then(decision -> switch (decision) {
            case Loop.Done<S, A>(var result) -> Recipe.premade(result);
            case Loop.Continue<S, A>(var next) -> loop(next, step);
        });
    }

    // === Accumulation recipes ===

    /**
     * Accumulates response data across multiple command exchanges, driven by
     * status word matching. Sends the initial command, then for each response:
     * <ul>
     *   <li>{@code continueSW} - include response data, send {@code more(response)}</li>
     *   <li>{@code doneSW} or any SW in {@code alsoDone} - include response data, stop</li>
     *   <li>anything else - fail with {@code errorMsg}</li>
     * </ul>
     *
     * <p>Covers SW-continuation patterns (GP GET STATUS with 6310,
     * ISO GET RESPONSE with 61xx) without exposing intermediate types:
     * <pre>{@code
     * // GP GET STATUS with 6310 continuation
     * var recipe = Cookbook.gather(
     *     cmd(INS_GET_STATUS, p1, p2, filter),
     *     0x6310, r -> cmd(INS_GET_STATUS, p1, p2 | 0x01, filter),
     *     0x9000, "GET STATUS failed",
     *     List.of(0x6A88, 0x6A86, 0x6A81));
     *
     * // ISO GET RESPONSE (61xx) with dynamic Le
     * var recipe = Cookbook.gather(
     *     someCmd,
     *     0x6100, r -> new CommandAPDU(0x00, 0xC0, 0x00, 0x00, r.getSW2()),
     *     0x9000, "command failed",
     *     List.of());
     * }</pre>
     *
     * @param initial    the first command to send
     * @param continueSW status word that means "more data available"
     * @param more       produces the next command from the current response
     * @param doneSW     status word that means "last chunk, stop"
     * @param errorMsg   error prefix for unexpected status words
     * @param alsoDone   additional status words that also mean "stop"
     * @return a recipe that produces the concatenated data from all exchanges
     */
    public static Recipe<byte[]> gather(CommandAPDU initial, int continueSW,
                                             Function<ResponseAPDU, CommandAPDU> more,
                                             int doneSW, String errorMsg,
                                             List<Integer> alsoDone) {
        return gather(initial, continueSW, more, ResponseAPDU::getData, doneSW, errorMsg, alsoDone);
    }

    /**
     * Like {@link #gather(CommandAPDU, int, Function, int, String, List)}
     * but with a custom data extractor applied to each response before
     * concatenation. Useful when response data needs stripping (e.g. unwrap
     * a TLV envelope) or transformation before accumulation.
     *
     * @param initial     the first command to send
     * @param continueSW  status word that means "more data available"
     * @param more        produces the next command from the current response
     * @param extractData extracts the bytes to gather from each response
     * @param doneSW      status word that means "last chunk, stop"
     * @param errorMsg    error prefix for unexpected status words
     * @param alsoDone    additional status words that also mean "stop"
     * @return a recipe that produces the concatenated extracted data
     */
    public static Recipe<byte[]> gather(CommandAPDU initial, int continueSW,
                                             Function<ResponseAPDU, CommandAPDU> more,
                                             Function<ResponseAPDU, byte[]> extractData,
                                             int doneSW, String errorMsg,
                                             List<Integer> alsoDone) {
        record Acc(CommandAPDU cmd, byte[] data) {}
        return loop(
                new Acc(initial, new byte[0]),
                acc -> send(acc.cmd, any()).then(r -> {
                    var sw = r.getSW();
                    if (sw == continueSW) {
                        return Recipe.premade(new Loop.Continue<>(
                                new Acc(more.apply(r), concat(acc.data, extractData.apply(r)))));
                    }
                    if (sw == doneSW || alsoDone.contains(sw)) {
                        return Recipe.premade(new Loop.Done<>(concat(acc.data, extractData.apply(r))));
                    }
                    return Recipe.error("%s (SW: %04X)".formatted(errorMsg, sw));
                }));
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
