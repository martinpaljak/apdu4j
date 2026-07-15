// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import apdu4j.core.BIBOSA;
import apdu4j.core.CommandAPDU;
import apdu4j.core.HexBytes;
import apdu4j.core.HexUtils;
import apdu4j.core.MockBIBO;
import apdu4j.core.ResponseAPDU;
import apdu4j.prefs.Preference;
import apdu4j.prefs.Preferences;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.testng.Assert.*;

// Each test documents one observable rule of the framework as a scenario:
// the mock script is the Given, the recipe the When, the asserts the Then.
public class ApduletteTest {

    private static final CommandAPDU SELECT = new CommandAPDU(0x00, 0xA4, 0x04, 0x00);
    private static final CommandAPDU READ = new CommandAPDU(0x00, 0xB0, 0x00, 0x00, 256);
    // A chef with no card: any transmit throws, proving the recipe needs no I/O
    private static final Chef OFFLINE = new MasterChef(MockBIBO.throwing());

    // Cooks a recipe against a fresh card scripted with the given responses.
    private static <T> T cook(Recipe<T> recipe, String... responses) {
        return new MasterChef(MockBIBO.of(responses)).cook(recipe);
    }

    // === Recipes compose into pipelines: send, map, then, and, consume, data extraction ===

    @Test
    void recipesComposeIntoPipelines() {
        // A card that answers SELECT with an FCI, READ BINARY with file data,
        // GET DATA with a 7-byte UID, then a 61xx GET RESPONSE round trip
        var mock = MockBIBO.of("6F07A5058001019000", "AABBCCDDEEFF009000", "010203040506079000", "6102", "AABB9000");
        var chef = Chef.of(mock);

        var fci = new AtomicReference<ResponseAPDU>();
        var uid = new AtomicReference<byte[]>();

        // SELECT, observe the FCI, size the READ from it, read the UID,
        // then branch on 61xx to fetch the leftover with GET RESPONSE
        var recipe = Cookbook.send(SELECT)
                .consume(fci::set)
                .map(r -> r.getData().length)
                .then(len -> Cookbook.data(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, len), b -> b.length))
                .and(Cookbook.uid())
                .consume(uid::set)
                .then(u -> Cookbook.send(List.of(SELECT), Cookbook.any()).then(r ->
                        r.getSW1() == 0x61
                                ? Cookbook.data(new CommandAPDU(0x00, 0xC0, 0x00, 0x00, r.getSW2()))
                                : Recipe.premade(u)));

        assertEquals(HexUtils.bin2hex(chef.cook(recipe)), "AABB");
        assertEquals(fci.get().getSW(), 0x9000);
        assertEquals(HexUtils.bin2hex(fci.get().getData()), "6F07A505800101");
        assertEquals(HexUtils.bin2hex(uid.get()), "01020304050607");
    }

    // === Recipes form a monad: premade is unit, then is bind ===

    @Test
    void monadicLaws() {
        // Each side of a law runs against an identically scripted card
        // and must produce the same value.
        Function<Integer, Recipe<Integer>> f = n -> Cookbook.data(READ).map(b -> n + b.length);
        Function<Integer, Recipe<Integer>> g = n -> Cookbook.data(READ).map(b -> n * b.length);
        var r = Cookbook.data(READ).map(b -> b.length);

        // Left identity: premade(x).then(f) == f(x)
        assertEquals(cook(Recipe.premade(40).then(f), "AABB9000"), cook(f.apply(40), "AABB9000"));

        // Right identity: r.then(premade) == r
        assertEquals(cook(r.then(Recipe::premade), "AABB9000"), cook(r, "AABB9000"));

        // Associativity: r.then(f).then(g) == r.then(x -> f(x).then(g))
        assertEquals(cook(r.then(f).then(g), "AA9000", "BBCC9000", "DDEEFF9000"),
                cook(r.then(x -> f.apply(x).then(g)), "AA9000", "BBCC9000", "DDEEFF9000"));

        // Unit refuses null, and never touches the transport
        assertThrows(NullPointerException.class, () -> Recipe.premade(null));
        assertEquals(OFFLINE.cook(Recipe.premade("done")), "done");
    }

    // === Card-tier errors recover with orElse, recover, optional, firstOf; scope follows placement ===

    @Test
    void cardErrorsRecoverWherePlaced() {
        var fallback = Recipe.premade(ResponseAPDU.of(0x6999));

        // orElse covers the whole chain: a failure on any step falls back
        var chained = Cookbook.send(SELECT).and(Cookbook.send(SELECT)).orElse(fallback);
        assertEquals(cook(chained, "9000", "6A82").getSW(), 0x6999);
        assertEquals(cook(chained, "6A82").getSW(), 0x6999);

        // recover inspects the blamed response of whichever step failed
        var inspected = Cookbook.send(SELECT).and(Cookbook.send(SELECT))
                .recover(err -> Recipe.premade(ResponseAPDU.of(err.sw() == 0x6A82 ? 0x6999 : 0x6666)));
        assertEquals(cook(inspected, "9000", "6A82").getSW(), 0x6999);

        // Recovery follows the receiver: attached to the first step only,
        // a second-step failure propagates
        var placed = Cookbook.send(SELECT).orElse(fallback).and(Cookbook.send(SELECT));
        assertThrows(KitchenDisaster.class, () -> cook(placed, "9000", "6A82"));

        // optional() absorbs card errors anywhere in the chain: SW mismatches
        // and data validation failures (3 bytes is not a UID length) alike
        assertEquals(cook(Cookbook.send(SELECT).and(Cookbook.send(SELECT)).optional(), "9000", "6A82"), Optional.empty());
        assertEquals(cook(Cookbook.send(SELECT).optional(), "9000").orElseThrow().getSW(), 0x9000);
        assertEquals(cook(Cookbook.uid().optional(), "AABBCC9000"), Optional.empty());
        assertEquals(cook(Cookbook.uid().optional(), "6A82"), Optional.empty());
        // 4- and 10-byte UIDs pass the length validation (7 is proven elsewhere)
        assertEquals(HexUtils.bin2hex(cook(Cookbook.uid(), "AABBCCDD9000")), "AABBCCDD");
        assertEquals(cook(Cookbook.uid(), "000102030405060708099000").length, 10);

        // Recipe.cardError is a card-tier failure without I/O: recover sees the blamed response
        var blamed = ResponseAPDU.of(0x6985);
        assertEquals(OFFLINE.cook(Recipe.<String>cardError(blamed, "denied").orElse(Recipe.premade("recovered"))),
                "recovered");
        var seen = OFFLINE.cook(Recipe.<String>cardError(blamed, "denied").recover(err -> {
            assertSame(err.response(), blamed);
            return Recipe.premade("SW=%04X".formatted(err.sw()));
        }));
        assertEquals(seen, "SW=6985");

        // firstOf tries alternatives in order; a multi-step alternative failing
        // midway moves on too; the last error propagates when all fail
        var alternatives = Cookbook.firstOf(List.of(Cookbook.send(SELECT), Cookbook.send(SELECT)));
        assertEquals(cook(alternatives, "6A82", "9000").getSW(), 0x9000);
        assertEquals(cook(alternatives, "9000").getSW(), 0x9000);
        assertThrows(KitchenDisaster.class, () -> cook(alternatives, "6A82", "6A82"));
        var multiStep = Cookbook.firstOf(List.of(Cookbook.send(SELECT).and(Cookbook.send(SELECT)), Cookbook.send(SELECT)));
        assertEquals(cook(multiStep, "9000", "6A82", "9000").getSW(), 0x9000);
        assertThrows(IllegalArgumentException.class, () -> Cookbook.firstOf(List.of()));

        // The success path evaluates the taster exactly once under orElse
        // and recover (double-evaluation regression)
        var count = new AtomicInteger();
        Recipe<ResponseAPDU> counted = prefs -> new PreparationStep.Ingredients<>(List.of(SELECT), List.of(),
                (responses, p) -> {
                    count.incrementAndGet();
                    return new Verdict.Ready<>(responses.getFirst());
                });
        cook(counted.orElse(fallback), "9000");
        cook(counted.recover(err -> Recipe.premade(err.response())), "9000");
        assertEquals(count.get(), 2);
    }

    // === Programmer-tier failures escape all recovery; disasters carry their payload ===

    @Test
    void programmerFailuresEscapeRecovery() {
        // Recipe.fail throws at prepare: then never runs, orElse and optional do not catch
        assertThrows(KitchenDisaster.class,
                () -> OFFLINE.cook(Recipe.fail("nope").then(v -> Recipe.premade("ok"))));
        assertThrows(KitchenDisaster.class,
                () -> OFFLINE.cook(Recipe.<String>fail("nope").orElse(Recipe.premade("no"))));
        assertThrows(KitchenDisaster.class, () -> OFFLINE.cook(Recipe.fail("nope").optional()));

        // The cause surfaces; prepare-time failures have no response and no preferences
        var root = new IllegalStateException("root");
        var disaster = expectThrows(KitchenDisaster.class, () -> OFFLINE.cook(Recipe.fail("nope", root)));
        assertSame(disaster.getCause(), root);
        assertNull(disaster.response());
        assertNull(disaster.preferences());

        // An unhandled card error becomes a disaster carrying the SW in the
        // message, the response and the accumulated preferences
        var cardSaidNo = expectThrows(KitchenDisaster.class,
                () -> cook(Cookbook.send(SELECT, 0x9000), "6A82"));
        assertTrue(cardSaidNo.getMessage().contains("6A82"));
        assertEquals(cardSaidNo.response().getSW(), 0x6A82);
        assertNotNull(cardSaidNo.preferences());

        // An unhandled cardError recipe blames its response
        var blamed = ResponseAPDU.of(0x6985);
        var denied = expectThrows(KitchenDisaster.class,
                () -> OFFLINE.cook(Recipe.cardError(blamed, "denied")));
        assertSame(denied.response(), blamed);
        assertTrue(denied.getMessage().contains("denied"));

        // An Error verdict must always blame a real response
        assertThrows(NullPointerException.class, () -> new Verdict.Error<>(null, "x"));

        // A recipe that never terminates is cut off by the iteration guard
        var spin = new Recipe<String>() {
            @Override
            public PreparationStep<String> prepare(Preferences prefs) {
                return new PreparationStep.Seasoned<>(this, new Preferences());
            }
        };
        var runaway = expectThrows(KitchenDisaster.class, () -> OFFLINE.cook(spin));
        assertTrue(runaway.getMessage().contains("iterations"));
    }

    // === Preferences flow into recipes: defaults, baseline, deferred construction ===

    @Test
    void preferencesDriveRecipes() {
        var maxLen = Preference.of("maxApduData", Integer.class, 255, false);
        Recipe<Integer> reader = prefs -> new PreparationStep.Premade<>(prefs.get(maxLen));

        // Defaults resolve, explicit values override
        assertEquals(OFFLINE.cook(reader), Integer.valueOf(255));
        assertEquals(OFFLINE.cook(reader, new Preferences().with(maxLen, 128)), Integer.valueOf(128));

        // A BIBOSA's preference sidecar seeds every recipe as the Chef baseline;
        // explicit prefs win on conflict
        var seeded = Chef.of(new BIBOSA(MockBIBO.throwing(), new Preferences().with(maxLen, 128)));
        assertEquals(seeded.cook(reader), Integer.valueOf(128));
        assertEquals(seeded.cook(reader, new Preferences().with(maxLen, 64)), Integer.valueOf(64));

        // deferred() builds the recipe at prepare-time from the whole bag; the
        // verifying mock proves the APDU bytes actually change with the preference
        var le = Preference.of("le", Integer.class, 256, false);
        var recipe = Cookbook.deferred(prefs -> Cookbook.send(
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[]{(byte) 0xA0}, prefs.get(le)),
                Cookbook.expect(0x9000)));
        assertEquals(new MasterChef(MockBIBO.with("00A4040001A000", "9000"))
                .cook(recipe).getSW(), 0x9000);
        assertEquals(new MasterChef(MockBIBO.with("00A4040001A00A", "9000"))
                .cook(recipe, new Preferences().with(le, 10)).getSW(), 0x9000);

        // depends() names a single key; the continuation receives only its resolved value
        var dependsRecipe = Cookbook.depends(le, leValue -> Cookbook.send(
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[]{(byte) 0xA0}, leValue),
                Cookbook.expect(0x9000)));
        assertEquals(new MasterChef(MockBIBO.with("00A4040001A00A", "9000"))
                .cook(dependsRecipe, new Preferences().with(le, 10)).getSW(), 0x9000);

        // preference() lifts a default key to its resolved value without I/O
        assertEquals(OFFLINE.cook(Cookbook.preference(le), new Preferences().with(le, 42)), Integer.valueOf(42));
    }

    // === Preferences flow out of recipes: NextStep and season accumulate, serve surfaces them ===

    @Test
    void recipesEmitPreferences() {
        var sessionId = Preference.parameter("sessionId", String.class, true);
        var extra = Preference.parameter("extra", Integer.class, false);

        // A taster's NextStep verdict enriches preferences for downstream steps
        Recipe<String> handshake = prefs -> new PreparationStep.Ingredients<>(
                List.of(new CommandAPDU(0x80, 0x50, 0x00, 0x00, 8)),
                List.of(),
                (responses, p) -> new Verdict.NextStep<>(
                        pp -> new PreparationStep.Premade<>(pp.valueOf(sessionId).orElseThrow()),
                        new Preferences().with(sessionId, HexUtils.bin2hex(responses.getFirst().getData()))));
        assertEquals(cook(handshake, "AABB9000"), "AABB");

        // serve() returns the dish: the value plus everything the chain accumulated
        var dish = new MasterChef(MockBIBO.of("AABB9000")).serve(handshake);
        assertEquals(dish.value(), "AABB");
        assertEquals(dish.preferences().valueOf(sessionId).orElseThrow(), "AABB");

        // Without contributions the dish carries the initial preferences unchanged
        var maxLen = Preference.of("maxApduData", Integer.class, 255, false);
        var initial = new Preferences().with(maxLen, 128);
        var plain = OFFLINE.serve(Recipe.premade("done"), initial);
        assertEquals(plain.value(), "done");
        assertEquals(plain.preferences(), initial);

        // Cookbook.season() injects preferences from mid-recipe code
        dish = OFFLINE.serve(Cookbook.season("hello", Preferences.of(sessionId, "world")));
        assertEquals(dish.value(), "hello");
        assertEquals(dish.preferences().valueOf(sessionId).orElseThrow(), "world");

        // Seasoned preferences are visible to downstream recipes
        dish = OFFLINE.serve(
                Recipe.premade("ABC")
                        .then(v -> Cookbook.season(v, Preferences.of(sessionId, v)))
                        .then(v -> Cookbook.deferred(prefs ->
                                Recipe.premade(prefs.valueOf(sessionId).orElse("missing")))));
        assertEquals(dish.value(), "ABC");
        assertEquals(dish.preferences().valueOf(sessionId).orElseThrow(), "ABC");

        // Nested season calls accumulate
        var intDish = OFFLINE.serve(
                Recipe.premade("A")
                        .then(v -> Cookbook.season(v, Preferences.of(sessionId, v)))
                        .then(v -> Cookbook.season(99, Preferences.of(extra, 99))));
        assertEquals(intDish.value(), Integer.valueOf(99));
        assertEquals(intDish.preferences().valueOf(sessionId).orElseThrow(), "A");
        assertEquals(intDish.preferences().valueOf(extra).orElseThrow(), Integer.valueOf(99));

        // With real I/O: season in a then() chain after a card exchange
        var ioChef = new MasterChef(MockBIBO.of("AABB9000", "9000"));
        var ioDish = ioChef.serve(
                Cookbook.send(new CommandAPDU(0x80, 0x50, 0x00, 0x00, 8))
                        .then(r -> Cookbook.season(r, Preferences.of(sessionId, HexUtils.bin2hex(r.getData()))))
                        .and(Cookbook.send(new CommandAPDU(0x00, 0x00, 0x00, 0x00))));
        assertEquals(ioDish.preferences().valueOf(sessionId).orElseThrow(), "AABB");

        // orElse and recover propagate a Seasoned step untouched
        dish = OFFLINE.serve(
                Recipe.premade("ok")
                        .then(v -> Cookbook.season(v, Preferences.of(sessionId, v)))
                        .orElse(Recipe.premade("fallback")));
        assertEquals(dish.value(), "ok");
        assertEquals(dish.preferences().valueOf(sessionId).orElseThrow(), "ok");
        dish = OFFLINE.serve(
                Recipe.premade("ok")
                        .then(v -> Cookbook.season(v, Preferences.of(sessionId, v)))
                        .recover(err -> Recipe.premade("recovered")));
        assertEquals(dish.value(), "ok");
        assertEquals(dish.preferences().valueOf(sessionId).orElseThrow(), "ok");

        // MiseEnPlaceChef handles Seasoned identically
        dish = new MiseEnPlaceChef().serve(
                Recipe.premade("test")
                        .then(v -> Cookbook.season(v, Preferences.of(sessionId, v)))
                        .then(v -> Cookbook.deferred(prefs ->
                                Recipe.premade(prefs.valueOf(sessionId).orElse("missing")))));
        assertEquals(dish.value(), "test");
        assertEquals(dish.preferences().valueOf(sessionId).orElseThrow(), "test");
    }

    // === Batches: one step, many commands; expected responses gate transmission ===

    @Test
    void batchesUseExpectedResponses() {
        var cmds = List.of(
                new CommandAPDU(0x80, 0xE8, 0x00, 0x00, new byte[]{0x01}),
                new CommandAPDU(0x80, 0xE8, 0x80, 0x01, new byte[]{0x02}));

        // All expected: every command sent, last response returned
        assertEquals(cook(Cookbook.send(cmds, 0x9000), "9000", "9000").getSW(), 0x9000);

        // A custom taster sees all responses together
        Recipe<Integer> counted = prefs -> new PreparationStep.Ingredients<>(cmds, List.of(),
                (responses, p) -> responses.stream().allMatch(r -> r.getSW() == 0x9000)
                        ? new Verdict.Ready<>(responses.size())
                        : new Verdict.Error<>(responses.getFirst(), "STORE DATA failed"));
        assertEquals(cook(counted, "9000", "9000"), Integer.valueOf(2));

        // allData concatenates data across responses; all() with a complainer names the failure
        assertEquals(HexUtils.bin2hex(cook(Cookbook.send(cmds, Cookbook.allData(0x9000)),
                "AABB9000", "CCDD9000")), "AABBCCDD");
        assertThrows(KitchenDisaster.class,
                () -> cook(Cookbook.send(cmds, Cookbook.allData(0x9000)), "AABB9000", "6A80"));
        var complainer = Cookbook.all(0x9000, (ResponseAPDU r) -> "block rejected with %04X".formatted(r.getSW()));
        assertEquals(cook(Cookbook.send(cmds, complainer), "9000", "9000").getSW(), 0x9000);
        var named = expectThrows(KitchenDisaster.class,
                () -> cook(Cookbook.send(cmds, complainer), "9000", "6A80"));
        assertTrue(named.getMessage().contains("block rejected with 6A80"));

        // An expectation mismatch stops transmission early: only two responses
        // scripted for three commands, and the third is never requested
        var three = List.of(
                new CommandAPDU(0x80, 0xE8, 0x00, 0x00, new byte[]{0x01}),
                new CommandAPDU(0x80, 0xE8, 0x00, 0x01, new byte[]{0x02}),
                new CommandAPDU(0x80, 0xE8, 0x80, 0x02, new byte[]{0x03}));
        assertThrows(KitchenDisaster.class, () -> cook(Cookbook.send(three, 0x9000), "9000", "6A80"));
        assertEquals(cook(Cookbook.send(three, 0x9000), "9000", "9000", "9000").getSW(), 0x9000);

        // The mismatch is a card-tier error like any other: orElse recovers
        var recovered = Cookbook.send(cmds, 0x9000).orElse(Cookbook.send(SELECT));
        assertEquals(cook(recovered, "9000", "6A80", "9000").getSW(), 0x9000);

        // An SW-only expectation matches any data; expected data must match
        // exactly, and the taster still owns the final verdict
        var readTwice = List.of(READ, new CommandAPDU(0x00, 0xB0, 0x00, 0x01, 256));
        Recipe<ResponseAPDU> swOnly = prefs -> new PreparationStep.Ingredients<>(
                List.of(readTwice.getFirst()), List.of(ResponseAPDU.OK), Cookbook.expect(0x9000));
        assertEquals(cook(swOnly, "AABBCAFE9000").getSW(), 0x9000);
        assertThrows(KitchenDisaster.class, () -> cook(swOnly, "AABB6A82"));

        Recipe<ResponseAPDU> exactData = prefs -> new PreparationStep.Ingredients<>(readTwice,
                List.of(ResponseAPDU.of("CAFE9000"), ResponseAPDU.of("CAFE9000")),
                Cookbook.check(r -> HexUtils.bin2hex(r.getData()).equals("CAFE"), "not CAFE"));
        assertEquals(cook(exactData, "CAFE9000", "CAFE9000").getSW(), 0x9000);
        // A data mismatch stops after the first response (a second transmit would deplete the mock)
        assertThrows(KitchenDisaster.class, () -> cook(exactData, "DEAD9000"));

        // Contract: empty batches and command/expectation size mismatches are rejected eagerly
        assertThrows(IllegalArgumentException.class, () -> Cookbook.send(List.of(), 0x9000));
        assertThrows(IllegalArgumentException.class, () -> Cookbook.send(List.of(), Cookbook.any()));
        assertThrows(IllegalArgumentException.class, () -> new PreparationStep.Ingredients<>(
                List.of(readTwice.getFirst()), List.of(ResponseAPDU.OK, ResponseAPDU.OK), Cookbook.expect(0x9000)));
        // Ingredients is never a "fake" step: it requires at least one command
        assertThrows(IllegalArgumentException.class, () -> new PreparationStep.Ingredients<>(
                List.of(), List.of(), Cookbook.any()));
    }

    // === MiseEnPlaceChef executes recipes without a card, from expected responses ===

    @Test
    void miseEnPlaceReplaysWithoutCard() {
        var chef = new MiseEnPlaceChef();
        var cmds = List.of(new CommandAPDU(0x80, 0xE8, 0x00, 0x00, new byte[]{0x01}));
        var ok = List.of(ResponseAPDU.OK);

        // Expected responses stand in for the card
        var batch = List.of(
                new CommandAPDU(0x80, 0xE8, 0x00, 0x00, new byte[]{0x01}),
                new CommandAPDU(0x80, 0xE8, 0x80, 0x01, new byte[]{0x02}));
        assertEquals(chef.cook(Cookbook.send(batch, 0x9000)).getSW(), 0x9000);

        // Without explicit expectations every command is assumed to answer 9000
        assertEquals(chef.cook(Cookbook.send(SELECT)).getSW(), 0x9000);

        // An expectation the taster rejects fails the dry run
        Recipe<ResponseAPDU> mismatch = prefs -> new PreparationStep.Ingredients<>(cmds,
                List.of(ResponseAPDU.of(0x6A80)), Cookbook.expect(0x9000));
        assertThrows(KitchenDisaster.class, () -> chef.cook(mismatch));

        // NextStep and preference threading work identically to a real card
        var sid = Preference.parameter("sid", String.class, true);
        Recipe<String> chained = prefs -> new PreparationStep.Ingredients<>(cmds, ok,
                (responses, p) -> new Verdict.NextStep<>(
                        pp -> new PreparationStep.Premade<>(pp.valueOf(sid).orElseThrow()),
                        new Preferences().with(sid, "ABC")));
        assertEquals(chef.cook(chained), "ABC");

        // Combinators preserve expectations: recipes stay dry-runnable through
        // then, orElse and recover
        Recipe<ResponseAPDU> base = prefs -> new PreparationStep.Ingredients<>(cmds, ok, Cookbook.expect(0x9000));
        for (var combined : List.<Recipe<ResponseAPDU>>of(
                base.then(Recipe::premade),
                base.orElse(Recipe.premade(ResponseAPDU.of(0x6A82))),
                base.recover(err -> Recipe.premade(err.response())))) {
            assertEquals(chef.cook(combined).getSW(), 0x9000);
        }
    }

    // === Looping and chunked accumulation ===

    @Test
    void loopsGatherChunkedData() {
        // loop() threads state until the step decides Done
        Recipe<Integer> countOk = Cookbook.loop(0, n ->
                Cookbook.send(READ, Cookbook.any()).then(r ->
                        r.getSW() == 0x9000
                                ? Recipe.premade(new Cookbook.Loop.Continue<>(n + 1))
                                : Recipe.premade(new Cookbook.Loop.Done<>(n))));
        assertEquals(cook(countOk, "9000", "9000", "6A82"), Integer.valueOf(2));

        // gather() accumulates response data across an SW-driven continuation
        // (the GP GET STATUS pattern: 6310 means more data available)
        var getStatus = new CommandAPDU(0x80, 0xF2, 0x80, 0x00, new byte[]{0x4F, 0x00});
        var gather = Cookbook.gather(getStatus, 0x6310,
                r -> new CommandAPDU(0x80, 0xF2, 0x80, 0x01, new byte[]{0x4F, 0x00}),
                0x9000, "GET STATUS failed", List.of(0x6A88));
        assertEquals(HexUtils.bin2hex(cook(gather, "AABB6310", "CCDD9000")), "AABBCCDD");
        assertEquals(HexUtils.bin2hex(cook(gather, "AABB6310", "9000")), "AABB");

        // A status word in alsoDone stops with what was gathered so far
        assertEquals(cook(gather, "6A88").length, 0);

        // Anything else is a card error carrying the message, recoverable like any other
        var failed = expectThrows(KitchenDisaster.class, () -> cook(gather, "6F00"));
        assertTrue(failed.getMessage().contains("GET STATUS failed"));
        assertEquals(cook(gather.orElse(Recipe.premade(new byte[0])), "6F00").length, 0);

        // The extractor overload transforms each chunk before concatenation
        var stripped = Cookbook.gather(getStatus, 0x6310, r -> getStatus,
                r -> Arrays.copyOfRange(r.getData(), 1, r.getData().length),
                0x9000, "failed", List.of());
        assertEquals(HexUtils.bin2hex(cook(stripped, "01AA6310", "01BB9000")), "AABB");

        // The general gather() drives a read loop from an explicit cursor and decides continuation
        // from the folded response - here a length bound, which the status-word gather() overloads
        // cannot express since they stop on a status word, not accumulated data. cont/done drop the
        // Loop wrapping ceremony.
        record Buf(byte[] data) {}
        var readUntil4 = Cookbook.gather(new Buf(new byte[0]), c -> READ, (c, r) -> {
            var next = new Buf(HexBytes.concatenate(c.data(), r.getData()));
            return next.data().length >= 4 ? Cookbook.done(next.data()) : Cookbook.cont(next);
        });
        assertEquals(HexUtils.bin2hex(cook(readUntil4, "AA9000", "BB9000", "CC9000", "DD9000")), "AABBCCDD");

        // A bad status word inside advance fails through the recipe channel, recoverable like any card error
        var strict = Cookbook.gather(new Buf(new byte[0]), c -> READ,
                (c, r) -> r.getSW() != 0x9000 ? Recipe.cardError(r, "read failed") : Cookbook.done(r.getData()));
        assertTrue(expectThrows(KitchenDisaster.class, () -> cook(strict, "6A82")).getMessage().contains("read failed"));
    }

    // === Bulk combinators: sequence, traverse, foldLeft, require ===

    @Test
    void bulkCombinatorsSequenceWork() {
        // sequence runs all recipes in order and collects results
        var lengths = Cookbook.sequence(List.of(
                Cookbook.data(READ).map(b -> b.length),
                Cookbook.data(READ).map(b -> b.length)));
        assertEquals(cook(lengths, "AA9000", "BBCC9000"), List.of(1, 2));

        // One failure fails the whole sequence
        assertThrows(KitchenDisaster.class, () -> cook(lengths, "AA9000", "6A82"));

        // Empty input needs no I/O
        assertEquals(OFFLINE.cook(Cookbook.sequence(List.of())), List.of());

        // traverse maps items to recipes and sequences them
        var sws = Cookbook.traverse(List.of(0x00, 0x01), p1 ->
                Cookbook.send(new CommandAPDU(0x00, 0xB0, p1, 0x00, 256)).map(ResponseAPDU::getSW));
        assertEquals(cook(sws, "9000", "9000"), List.of(0x9000, 0x9000));

        // foldLeft threads the accumulator through each step
        var total = Cookbook.foldLeft(0, List.of(0x00, 0x01, 0x02), (acc, p1) ->
                Cookbook.data(new CommandAPDU(0x00, 0xB0, p1, 0x00, 256)).map(b -> acc + b.length));
        assertEquals(cook(total, "AA9000", "BBCC9000", "DD9000"), Integer.valueOf(4));

        // require unwraps a present optional and fails an empty one
        var probe = Cookbook.send(READ).optional();
        assertEquals(cook(Cookbook.require(probe, "file missing"), "9000").getSW(), 0x9000);
        var missing = expectThrows(KitchenDisaster.class,
                () -> cook(Cookbook.require(probe, "file missing"), "6A82"));
        assertTrue(missing.getMessage().contains("file missing"));
    }

    // === Tasters compose: expect variants, check, refine, map, tryMap ===

    @Test
    void tastersCompose() {
        // expect() accepts exactly the named status words; zero of them is a programming error
        Recipe<ResponseAPDU> either = prefs -> new PreparationStep.Ingredients<>(List.of(SELECT), List.of(),
                Cookbook.expect(0x9000, 0x6283));
        assertEquals(cook(either, "6283").getSW(), 0x6283);
        var rejected = expectThrows(KitchenDisaster.class, () -> cook(either, "6A82"));
        assertTrue(rejected.getMessage().contains("9000"));
        assertThrows(IllegalArgumentException.class, Cookbook::expect);

        // A complainer turns the failing response into a domain-specific
        // message and stays silent on success
        var refusable = Cookbook.send(SELECT, Cookbook.expect(0x9000,
                r -> "select refused with %04X".formatted(r.getSW())));
        assertEquals(cook(refusable, "9000").getSW(), 0x9000);
        var complained = expectThrows(KitchenDisaster.class, () -> cook(refusable, "6A82"));
        assertTrue(complained.getMessage().contains("select refused with 6A82"));

        // check() applies an arbitrary predicate to the response
        Recipe<ResponseAPDU> nonEmpty = prefs -> new PreparationStep.Ingredients<>(List.of(SELECT), List.of(),
                Cookbook.check(r -> r.getData().length > 0, "empty response"));
        assertEquals(HexUtils.bin2hex(cook(nonEmpty, "AABB9000").getData()), "AABB");
        assertThrows(KitchenDisaster.class, () -> cook(nonEmpty, "9000"));

        // tryMap turns a throwing parser into a card-tier error blaming the response
        var parsed = Cookbook.send(SELECT, Cookbook.expect(0x9000)
                .tryMap(r -> Integer.parseInt(HexUtils.bin2hex(r.getData()), 16)));
        assertEquals(cook(parsed, "00429000"), Integer.valueOf(0x42));
        assertThrows(KitchenDisaster.class, () -> cook(parsed, "6A82"));
        var garbage = expectThrows(KitchenDisaster.class, () -> cook(Cookbook.send(SELECT,
                Cookbook.expect(0x9000).tryMap(r -> {
                    throw new IllegalArgumentException("bad TLV");
                })), "9000"));
        assertTrue(garbage.getMessage().contains("bad TLV"));
        // A messageless exception still yields a readable error
        var messageless = expectThrows(KitchenDisaster.class, () -> cook(Cookbook.send(SELECT,
                Cookbook.expect(0x9000).tryMap(r -> {
                    throw new IllegalStateException();
                })), "9000"));
        assertTrue(messageless.getMessage().contains("IllegalStateException"));

        // refine, map and tryMap also compose over a NextStep verdict from a continuing taster
        Taster<String> continuing = (responses, prefs) -> new Verdict.NextStep<>(Recipe.premade("AB"));
        Recipe<String> lowered = prefs -> new PreparationStep.Ingredients<>(List.of(SELECT), List.of(),
                continuing.map(String::toLowerCase));
        assertEquals(cook(lowered, "9000"), "ab");
        Recipe<String> refined = prefs -> new PreparationStep.Ingredients<>(List.of(SELECT), List.of(),
                continuing.refine(s -> s.length() == 2, "wrong length"));
        assertEquals(cook(refined, "9000"), "AB");
        // A failing refinement after NextStep blames the last response
        Recipe<String> tooLong = prefs -> new PreparationStep.Ingredients<>(List.of(SELECT), List.of(),
                continuing.refine(s -> s.length() > 2, "wrong length"));
        var blamed = expectThrows(KitchenDisaster.class, () -> cook(tooLong, "9000"));
        assertEquals(blamed.response().getSW(), 0x9000);
        assertTrue(blamed.getMessage().contains("wrong length"));
        Recipe<Integer> measured = prefs -> new PreparationStep.Ingredients<>(List.of(SELECT), List.of(),
                continuing.tryMap(String::length));
        assertEquals(cook(measured, "9000"), Integer.valueOf(2));
        Recipe<String> cut = prefs -> new PreparationStep.Ingredients<>(List.of(SELECT), List.of(),
                continuing.tryMap(s -> s.substring(5)));
        assertThrows(KitchenDisaster.class, () -> cook(cut, "9000"));
    }
}
