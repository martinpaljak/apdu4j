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
import apdu4j.core.HexUtils;
import apdu4j.core.MockBIBO;
import apdu4j.core.ResponseAPDU;
import apdu4j.prefs.Preference;
import apdu4j.prefs.Preferences;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.*;

// Usage examples that double as tests. Each test shows a realistic scenario.
public class ApduletteTest {

    // === Basic: send an APDU and inspect the response ===

    @Test
    void sendApdu() {
        var mock = MockBIBO.of("CAFE9000");
        var chef = new SousChef(mock);

        var recipe = Cookbook.send(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, 256));
        var response = chef.cook(recipe, new Preferences());

        assertEquals(response.getSW(), 0x9000);
        assertEquals(HexUtils.bin2hex(response.getData()), "CAFE");
    }

    // === SELECT applet by AID, then read UID ===

    @Test
    void selectAppletThenReadUid() {
        var mock = MockBIBO.of("9000", "010203040506079000");
        var chef = new SousChef(mock);

        var recipe = Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00))
                .and(Cookbook.uid());

        var uid = chef.cook(recipe, new Preferences());
        assertEquals(HexUtils.bin2hex(uid), "01020304050607");
    }

    // === SELECT, parse FCI, then use data in next step ===

    @Test
    void selectAndUseFciData() {
        var mock = MockBIBO.of("6F07A5058001019000", "AABB9000");
        var chef = new SousChef(mock);

        // Select, extract FCI data length, use it in a follow-up READ BINARY
        var recipe = Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00))
                .map(fci -> fci.getData().length)
                .then(len -> Cookbook.send(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, len)));

        var response = chef.cook(recipe, new Preferences());
        assertEquals(response.getSW(), 0x9000);
    }

    // === Transform result with map ===

    @Test
    void mapTransformsResult() {
        var mock = MockBIBO.of("DEADBEEF9000");
        var chef = new SousChef(mock);

        var recipe = Cookbook.send(new CommandAPDU(0x00, 0xCA, 0x00, 0x00, 256))
                .map(r -> r.getData().length);

        assertEquals(chef.cook(recipe, new Preferences()), Integer.valueOf(4));
    }

    // === Premade values need no I/O ===

    @Test
    void premadeBypassesTransport() {
        // MockBIBO.throwing() would fail on any transceive - premade never touches it
        var chef = new SousChef(MockBIBO.throwing());
        assertEquals(chef.cook(Recipe.premade("done"), new Preferences()), "done");
    }

    // === Side-effect with consume ===

    @Test
    void consumeObservesValue() {
        var mock = MockBIBO.of("9000", "AABBCCDD9000");
        var chef = new SousChef(mock);
        var observed = new AtomicReference<ResponseAPDU>();

        var recipe = Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00))
                .consume(observed::set)
                .and(Cookbook.uid());

        var uid = chef.cook(recipe, new Preferences());
        assertEquals(HexUtils.bin2hex(uid), "AABBCCDD");
        assertNotNull(observed.get());
        assertEquals(observed.get().getSW(), 0x9000);
    }

    // === Handle SELECT failure with orElse fallback ===

    @Test
    void orElseFallsBackOnError() {
        // First SELECT fails with 6A82, fallback SELECT succeeds
        var mock = MockBIBO.of("6A82", "9000");
        var chef = new SousChef(mock);

        var recipe = Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00))
                .orElse(Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00)));

        var response = chef.cook(recipe, new Preferences());
        assertEquals(response.getSW(), 0x9000);
    }

    // === Recover with error inspection ===

    @Test
    void recoverInspectsErrorSW() {
        var mock = MockBIBO.of("6A82");
        var chef = new SousChef(mock);

        // Recover from error: pass through the actual card response
        var recipe = Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00))
                .recover(err -> Recipe.premade(err.response()));

        var result = chef.cook(recipe, new Preferences());
        assertEquals(result.getSW(), 0x6A82);
    }

    // === Unhandled card error includes SW in message ===

    @Test
    void unhandledErrorIncludesSWInMessage() {
        var mock = MockBIBO.of("6A82");
        var chef = new SousChef(mock);

        try {
            chef.cook(Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00)), new Preferences());
            fail("Expected KitchenDisaster");
        } catch (KitchenDisaster e) {
            assertTrue(e.getMessage().contains("6A82"));
        }
    }

    // === Validate short-circuits on bad data ===

    @Test(expectedExceptions = IllegalStateException.class)
    void validateRejectsBadValue() {
        var mock = MockBIBO.of("9000");
        var chef = new SousChef(mock);

        // Select succeeds but response data is empty - validation fails
        var recipe = Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00))
                .validate(r -> r.getData().length > 0, () -> new IllegalStateException("empty FCI"));

        chef.cook(recipe, new Preferences());
    }

    // === Batch: multiple commands in one step ===

    @Test
    void batchCommandsEvaluatedTogether() {
        var mock = MockBIBO.of("9000", "9000");
        var chef = new SousChef(mock);

        // Two STORE DATA commands as a batch; taster checks all responses
        Recipe<Integer> recipe = prefs -> new PreparationStep.Ingredients<>(
                List.of(
                        new CommandAPDU(0x80, 0xE8, 0x00, 0x00, new byte[]{0x01}),
                        new CommandAPDU(0x80, 0xE8, 0x80, 0x01, new byte[]{0x02})
                ),
                List.of(),
                (responses, p) -> {
                    var allOk = responses.stream().allMatch(r -> r.getSW() == 0x9000);
                    var bad = responses.stream().filter(r -> r.getSW() != 0x9000).findFirst();
                    return bad.<Verdict<Integer>>map(r -> new Verdict.Error<>(r, "STORE DATA failed"))
                            .orElse(new Verdict.Ready<>(responses.size()));
                }
        );

        assertEquals(chef.cook(recipe, new Preferences()), Integer.valueOf(2));
    }

    // === Preferences drive recipe behavior ===

    @Test
    void preferencesControlRecipeOutput() {
        var maxLen = Preference.of("maxApduData", Integer.class, 255, false);

        // Recipe reads max APDU data length to decide how to chunk
        Recipe<Integer> recipe = prefs -> {
            var max = prefs.get(maxLen);
            return new PreparationStep.Premade<>(max);
        };

        var chef = new SousChef(MockBIBO.of());
        assertEquals(chef.cook(recipe, new Preferences()), Integer.valueOf(255));
        assertEquals(chef.cook(recipe, new Preferences().with(maxLen, 128)), Integer.valueOf(128));
    }

    // === NextStep enriches preferences for downstream ===

    @Test
    void nextStepThreadsPreferences() {
        var sessionId = Preference.parameter("sessionId", String.class, true);
        var mock = MockBIBO.of("AABB9000");
        var chef = new SousChef(mock);

        // First step: send INIT UPDATE, extract session ID, pass it downstream
        Recipe<String> recipe = prefs -> new PreparationStep.Ingredients<>(
                List.of(new CommandAPDU(0x80, 0x50, 0x00, 0x00, 8)),
                List.of(),
                (responses, p) -> {
                    var sid = HexUtils.bin2hex(responses.get(0).getData());
                    return new Verdict.NextStep<>(
                            pp -> new PreparationStep.Premade<>(pp.valueOf(sessionId).orElseThrow()),
                            new Preferences().with(sessionId, sid)
                    );
                }
        );

        assertEquals(chef.cook(recipe, new Preferences()), "AABB");
    }

    // === serve() surfaces accumulated preferences ===

    @Test
    void serveSurfacesAccumulatedPreferences() {
        var sessionId = Preference.parameter("sessionId", String.class, true);
        var mock = MockBIBO.of("AABB9000");
        var chef = new SousChef(mock);

        Recipe<String> recipe = prefs -> new PreparationStep.Ingredients<>(
                List.of(new CommandAPDU(0x80, 0x50, 0x00, 0x00, 8)),
                List.of(),
                (responses, p) -> {
                    var sid = HexUtils.bin2hex(responses.get(0).getData());
                    return new Verdict.NextStep<>(
                            pp -> new PreparationStep.Premade<>(pp.valueOf(sessionId).orElseThrow()),
                            new Preferences().with(sessionId, sid)
                    );
                }
        );

        var dish = chef.serve(recipe, new Preferences());
        assertEquals(dish.value(), "AABB");
        assertEquals(dish.preferences().valueOf(sessionId).orElseThrow(), "AABB");
    }

    @Test
    void serveReturnsInitialPrefsWhenNoNextStep() {
        var maxLen = Preference.of("maxApduData", Integer.class, 255, false);
        var chef = new SousChef(MockBIBO.throwing());

        var initial = new Preferences().with(maxLen, 128);
        var dish = chef.serve(Recipe.premade("done"), initial);

        assertEquals(dish.value(), "done");
        assertEquals(dish.preferences(), initial);
    }

    // === Recipe.error always fails ===

    @Test(expectedExceptions = KitchenDisaster.class)
    void errorRecipeAlwaysFails() {
        var chef = new SousChef(MockBIBO.of());
        chef.cook(Recipe.error("impossible"), new Preferences());
    }

    // === Monadic composition: chain three operations ===

    @Test
    void threeStepPipeline() {
        // SELECT -> READ BINARY -> parse length
        var mock = MockBIBO.of("9000", "0102030405060708090A9000");
        var chef = new SousChef(mock);

        var recipe = Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00))
                .and(Cookbook.send(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, 256)))
                .map(r -> r.getData().length);

        assertEquals(chef.cook(recipe, new Preferences()), Integer.valueOf(10));
    }

    // === Recipe.premade(null) is rejected ===

    @Test(expectedExceptions = NullPointerException.class)
    void premadeRejectsNull() {
        Recipe.premade(null);
    }

    // === Cookbook.send() combines command + SW check ===

    @Test
    void sendChecksExpectedSW() {
        var mock = MockBIBO.of("9000");
        var chef = new SousChef(mock);

        var result = chef.cook(Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00)), new Preferences());
        assertEquals(result.getSW(), 0x9000);
    }

    @Test(expectedExceptions = KitchenDisaster.class)
    void sendRejectsUnexpectedSW() {
        var mock = MockBIBO.of("6A82");
        var chef = new SousChef(mock);
        chef.cook(Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00), 0x9000), new Preferences());
    }

    // === Cookbook.expect() taster factory ===

    @Test
    void expectAcceptsMatchingSW() {
        var mock = MockBIBO.of("6110");
        var chef = new SousChef(mock);
        // Accept SW=6110 explicitly
        Recipe<ResponseAPDU> recipe = prefs -> new PreparationStep.Ingredients<>(
                List.of(new CommandAPDU(0x00, 0xA4, 0x04, 0x00)),
                List.of(),
                Cookbook.expect(0x6110));
        var result = chef.cook(recipe, new Preferences());
        assertEquals(result.getSW(), 0x6110);
    }

    // === Cookbook.check() with custom predicate ===

    @Test
    void checkWithPredicate() {
        var mock = MockBIBO.of("AABB9000");
        var chef = new SousChef(mock);
        Recipe<ResponseAPDU> recipe = prefs -> new PreparationStep.Ingredients<>(
                List.of(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, 256)),
                List.of(),
                Cookbook.check(r -> r.getData().length > 0, "empty response"));
        var result = chef.cook(recipe, new Preferences());
        assertEquals(HexUtils.bin2hex(result.getData()), "AABB");
    }

    // === Cookbook.data() extracts bytes on 9000 ===

    @Test
    void dataExtractsBytesOn9000() {
        var mock = MockBIBO.of("DEADBEEF9000");
        var chef = new SousChef(mock);
        var uid = chef.cook(Cookbook.uid(), new Preferences());
        assertEquals(HexUtils.bin2hex(uid), "DEADBEEF");
    }

    // === deferred() defers APDU construction to Preferences ===

    @Test
    void sendBuildsApduFromPreferences() {
        var le = Preference.of("le", Integer.class, 256, false);

        // Recipe adapts Le from Preferences at prepare-time
        var recipe = Cookbook.deferred(prefs -> Cookbook.send(
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[]{(byte) 0xA0}, prefs.get(le)),
                Cookbook.expect(0x9000)));

        // With default Le=256: SELECT A0 with Le=0x00 (short encoding of 256)
        var mock256 = MockBIBO.with("00A40400" + "01" + "A0" + "00", "9000");
        assertEquals(new SousChef(mock256).cook(recipe, new Preferences()).getSW(), 0x9000);

        // With Le=10: same command but Le=0x0A - different APDU bytes
        var mock10 = MockBIBO.with("00A40400" + "01" + "A0" + "0A", "9000");
        assertEquals(new SousChef(mock10).cook(recipe, new Preferences().with(le, 10)).getSW(), 0x9000);
    }

    // === Explicit GET RESPONSE chaining at Recipe layer ===

    @Test
    void explicitGetResponseChaining() {
        // Card responds 6102 (2 bytes available), then GET RESPONSE returns the data
        var mock = MockBIBO.of("6102", "AABB9000");
        var chef = new SousChef(mock);

        var cmd = new CommandAPDU(0x00, 0xA4, 0x04, 0x00);
        var recipe = Cookbook.send(List.of(cmd), Cookbook.any()).then(r -> {
            if (r.getSW1() == 0x61) {
                return Cookbook.send(new CommandAPDU(cmd.getCLA(), 0xC0, 0x00, 0x00, r.getSW2()));
            }
            return Recipe.premade(r);
        });

        var result = chef.cook(recipe, new Preferences());
        assertEquals(result.getSW(), 0x9000);
        assertEquals(HexUtils.bin2hex(result.getData()), "AABB");
    }

    // === optional() absorbs card errors, propagates Failed ===

    @Test
    void optionalAbsorbsCardError() {
        var chef = new SousChef(MockBIBO.of("6A82"));

        // Card error -> Optional.empty()
        var recipe = Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00)).optional();
        assertEquals(chef.cook(recipe, new Preferences()), Optional.empty());

        // Success -> Optional.of(value)
        var chef2 = new SousChef(MockBIBO.of("9000"));
        var result = chef2.cook(
                Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00)).optional(),
                new Preferences());
        assertTrue(result.isPresent());
        assertEquals(result.get().getSW(), 0x9000);

        // Failed (prepare-time error) still throws
        assertThrows(KitchenDisaster.class,
                () -> new SousChef(MockBIBO.of()).cook(Recipe.error("nope").optional(), new Preferences()));
    }

    // === firstOf() tries alternatives in order ===

    @Test
    void firstOfTriesAlternatives() {
        // First SELECT fails, second succeeds
        var mock = MockBIBO.of("6A82", "9000");
        var chef = new SousChef(mock);

        var recipe = Cookbook.firstOf(List.of(
                Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00)),
                Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00))
        ));
        assertEquals(chef.cook(recipe, new Preferences()).getSW(), 0x9000);

        // First succeeds - second never tried
        var mock2 = MockBIBO.of("9000");
        assertEquals(new SousChef(mock2).cook(recipe, new Preferences()).getSW(), 0x9000);

        // All fail - last error propagates
        var mock3 = MockBIBO.of("6A82", "6A82");
        assertThrows(KitchenDisaster.class, () -> new SousChef(mock3).cook(recipe, new Preferences()));

        // Empty list
        assertThrows(KitchenDisaster.class,
                () -> new SousChef(MockBIBO.of()).cook(Cookbook.firstOf(List.of()), new Preferences()));
    }

    // === Cookbook batch send ===

    @Test
    void batchSendChecksAllResponses() {
        var cmds = List.of(
                new CommandAPDU(0x80, 0xE8, 0x00, 0x00, new byte[]{0x01}),
                new CommandAPDU(0x80, 0xE8, 0x80, 0x01, new byte[]{0x02})
        );

        // All 9000 - returns last response
        var mock = MockBIBO.of("9000", "9000");
        var result = new SousChef(mock).cook(Cookbook.send(cmds, 0x9000), new Preferences());
        assertEquals(result.getSW(), 0x9000);

        // Second fails - error
        var mock2 = MockBIBO.of("9000", "6A80");
        assertThrows(KitchenDisaster.class,
                () -> new SousChef(mock2).cook(Cookbook.send(cmds, 0x9000), new Preferences()));

        // Empty list - rejected
        assertThrows(IllegalArgumentException.class, () -> Cookbook.send(List.of(), 0x9000));
    }

    // === orElse/recover taster called exactly once (double-eval regression) ===

    @Test
    void tasterCalledExactlyOnce() {
        var count = new AtomicInteger(0);

        // Taster that counts invocations; returns Ready on 9000
        Recipe<ResponseAPDU> recipe = prefs -> new PreparationStep.Ingredients<>(
                List.of(new CommandAPDU(0x00, 0xA4, 0x04, 0x00)),
                List.of(),
                (responses, p) -> {
                    count.incrementAndGet();
                    var r = responses.getFirst();
                    return r.getSW() == 0x9000
                            ? new Verdict.Ready<>(r)
                            : new Verdict.Error<>(r, "bad SW");
                });

        // orElse on success path - taster must be called exactly once
        var mock = MockBIBO.of("9000");
        var fallback = Cookbook.send(new CommandAPDU(0x00, 0x00, 0x00, 0x00));
        new SousChef(mock).cook(recipe.orElse(fallback), new Preferences());
        assertEquals(count.get(), 1);

        // recover on success path - taster must be called exactly once
        count.set(0);
        var mock2 = MockBIBO.of("9000");
        new SousChef(mock2).cook(recipe.recover(err -> Recipe.premade(err.response())), new Preferences());
        assertEquals(count.get(), 1);
    }

    // === Failed propagates through then, recoverable via orElse ===

    @Test
    void failedPropagation() {
        var chef = new SousChef(MockBIBO.of());

        // Failed propagates through then - continuation never runs
        assertThrows(KitchenDisaster.class,
                () -> chef.cook(Recipe.error("nope").then(v -> Recipe.premade("ok")), new Preferences()));

        // Failed recoverable via orElse
        var result = chef.cook(
                Recipe.<String>error("nope").orElse(Recipe.premade("recovered")),
                new Preferences());
        assertEquals(result, "recovered");
    }

    // === Expected responses: early termination ===

    @Test
    void earlyTerminationOnExpectationMismatch() {
        // 3 commands, 2nd response has wrong SW. Only 2 responses provided -
        // if SousChef tried to send command 3, MockBIBO would throw "depleted"
        var mock = MockBIBO.of("9000", "6A80");
        var chef = new SousChef(mock);

        var cmds = List.of(
                new CommandAPDU(0x80, 0xE8, 0x00, 0x00, new byte[]{0x01}),
                new CommandAPDU(0x80, 0xE8, 0x00, 0x01, new byte[]{0x02}),
                new CommandAPDU(0x80, 0xE8, 0x80, 0x02, new byte[]{0x03})
        );
        var recipe = Cookbook.send(cmds, 0x9000);
        assertThrows(KitchenDisaster.class,
                () -> chef.cook(recipe, new Preferences()));
    }

    // === Expected responses: SW + optional data matching ===

    @Test
    void swOnlyExpectationMatchesAnyData() {
        // SW-only expected (no data): matches any response with that SW
        var expected = List.of(ResponseAPDU.OK);
        var cmd = List.of(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, 256));
        Recipe<ResponseAPDU> recipe = prefs -> new PreparationStep.Ingredients<>(cmd, expected, Cookbook.expect(0x9000));

        // Response with data + 9000 - passes (SW matches, no data check)
        var mock = MockBIBO.of("AABBCAFE9000");
        var result = new SousChef(mock).cook(recipe, new Preferences());
        assertEquals(result.getSW(), 0x9000);

        // Response with wrong SW - fails
        var mock2 = MockBIBO.of("AABB6A82");
        assertThrows(KitchenDisaster.class,
                () -> new SousChef(mock2).cook(recipe, new Preferences()));
    }

    @Test
    void dataExpectationRequiresExactMatch() {
        // Expected data=CAFE, SW=9000: must match both
        var expected = List.of(ResponseAPDU.of("CAFE9000"));
        var cmd = List.of(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, 256));
        Recipe<ResponseAPDU> recipe = prefs -> new PreparationStep.Ingredients<>(cmd, expected, Cookbook.expect(0x9000));

        // Exact data match - passes
        var mock = MockBIBO.of("CAFE9000");
        var result = new SousChef(mock).cook(recipe, new Preferences());
        assertEquals(result.getSW(), 0x9000);

        // Different data, same SW - fails
        var mock2 = MockBIBO.of("DEAD9000");
        assertThrows(KitchenDisaster.class,
                () -> new SousChef(mock2).cook(recipe, new Preferences()));
    }

    @Test
    void expectationsPassAllCommandsSent() {
        // All 3 match - taster should see all 3 responses
        var mock = MockBIBO.of("9000", "9000", "9000");
        var chef = new SousChef(mock);

        var cmds = List.of(
                new CommandAPDU(0x80, 0xE8, 0x00, 0x00, new byte[]{0x01}),
                new CommandAPDU(0x80, 0xE8, 0x00, 0x01, new byte[]{0x02}),
                new CommandAPDU(0x80, 0xE8, 0x80, 0x02, new byte[]{0x03})
        );
        var result = chef.cook(Cookbook.send(cmds, 0x9000), new Preferences());
        assertEquals(result.getSW(), 0x9000);
    }

    // === MiseEnPlaceChef: pre-computation without I/O ===

    @Test
    void miseEnPlaceExecutesWithExpectations() {
        var chef = new MiseEnPlaceChef();

        var cmds = List.of(
                new CommandAPDU(0x80, 0xE8, 0x00, 0x00, new byte[]{0x01}),
                new CommandAPDU(0x80, 0xE8, 0x80, 0x01, new byte[]{0x02})
        );
        var result = chef.cook(Cookbook.send(cmds, 0x9000), new Preferences());
        assertEquals(result.getSW(), 0x9000);
    }

    @Test
    void miseEnPlaceDefaultsToOkWithoutExpectations() {
        var chef = new MiseEnPlaceChef();
        // No expectations - MiseEnPlaceChef assumes 9000 for each command
        var result = chef.cook(Cookbook.send(new CommandAPDU(0x00, 0xA4, 0x04, 0x00)), new Preferences());
        assertEquals(result.getSW(), 0x9000);
    }

    @Test
    void miseEnPlaceThrowsOnExpectedMismatch() {
        var chef = new MiseEnPlaceChef();
        var cmds = List.of(new CommandAPDU(0x80, 0xE8, 0x00, 0x00, new byte[]{0x01}));
        // Expected 6A80, taster checks for 9000 - mismatch -> Error verdict -> KitchenDisaster
        var expected = List.of(ResponseAPDU.of(0x6A80));
        Recipe<ResponseAPDU> recipe = prefs -> new PreparationStep.Ingredients<>(cmds, expected, Cookbook.expect(0x9000));
        assertThrows(KitchenDisaster.class, () -> chef.cook(recipe, new Preferences()));
    }

    @Test
    void miseEnPlaceChainsThroughNextStep() {
        var chef = new MiseEnPlaceChef();
        var sessionId = apdu4j.prefs.Preference.parameter("sid", String.class, true);

        // Batch with expectations -> NextStep -> premade value using preferences
        var cmds = List.of(new CommandAPDU(0x80, 0x50, 0x00, 0x00, 8));
        var expected = List.of(ResponseAPDU.OK);

        Recipe<String> recipe = prefs -> new PreparationStep.Ingredients<>(cmds, expected,
                (responses, p) -> new Verdict.NextStep<>(
                        pp -> new PreparationStep.Premade<>(pp.valueOf(sessionId).orElseThrow()),
                        new Preferences().with(sessionId, "ABC")));

        assertEquals(chef.cook(recipe, new Preferences()), "ABC");
    }

    // === Combinators preserve expectations ===

    @Test
    void thenPreservesExpectations() {
        var chef = new MiseEnPlaceChef();
        var cmds = List.of(new CommandAPDU(0x80, 0xE8, 0x00, 0x00, new byte[]{0x01}));
        var expected = List.of(ResponseAPDU.OK);

        // Recipe with expectations, chained via then
        Recipe<ResponseAPDU> base = prefs -> new PreparationStep.Ingredients<>(cmds, expected, Cookbook.expect(0x9000));
        var recipe = base.then(r -> Recipe.premade(r.getSW()));

        // MiseEnPlaceChef can execute - expectations survived then()
        assertEquals(chef.cook(recipe, new Preferences()), Integer.valueOf(0x9000));
    }

    @Test
    void orElsePreservesExpectations() {
        var chef = new MiseEnPlaceChef();
        var cmds = List.of(new CommandAPDU(0x80, 0xE8, 0x00, 0x00, new byte[]{0x01}));
        var expected = List.of(ResponseAPDU.OK);

        Recipe<ResponseAPDU> base = prefs -> new PreparationStep.Ingredients<>(cmds, expected, Cookbook.expect(0x9000));
        var recipe = base.orElse(Recipe.premade(ResponseAPDU.of(0x6A82)));

        assertEquals(chef.cook(recipe, new Preferences()).getSW(), 0x9000);
    }

    @Test
    void recoverPreservesExpectations() {
        var chef = new MiseEnPlaceChef();
        var cmds = List.of(new CommandAPDU(0x80, 0xE8, 0x00, 0x00, new byte[]{0x01}));
        var expected = List.of(ResponseAPDU.OK);

        Recipe<ResponseAPDU> base = prefs -> new PreparationStep.Ingredients<>(cmds, expected, Cookbook.expect(0x9000));
        var recipe = base.recover(err -> Recipe.premade(err.response()));

        assertEquals(chef.cook(recipe, new Preferences()).getSW(), 0x9000);
    }

    // === Cookbook.send(List, int) produces expectations ===

    @Test
    void batchSendGeneratesExpectations() {
        var cmds = List.of(
                new CommandAPDU(0x80, 0xE8, 0x00, 0x00, new byte[]{0x01}),
                new CommandAPDU(0x80, 0xE8, 0x80, 0x01, new byte[]{0x02})
        );
        var recipe = Cookbook.send(cmds, 0x9000);
        var step = recipe.prepare(new Preferences());

        // Verify it's Ingredients with expectations
        assertTrue(step instanceof PreparationStep.Ingredients<?>);
        var ing = (PreparationStep.Ingredients<?>) step;
        assertEquals(ing.expected().size(), 2);
        assertEquals(ing.expected().get(0).getSW(), 0x9000);
        assertEquals(ing.expected().get(1).getSW(), 0x9000);
    }

    // === Seasoned: mid-recipe preference injection ===

    @Test
    void seasonInjectsPreferencesFromMidRecipeCode() {
        var discovered = Preference.parameter("discovered", String.class, true);
        var extra = Preference.parameter("extra", Integer.class, false);

        // Basic: Cookbook.season() emits preferences visible in Dish
        var chef = new SousChef(MockBIBO.throwing());
        var dish = chef.serve(Cookbook.season("hello", Preferences.of(discovered, "world")));
        assertEquals(dish.value(), "hello");
        assertEquals(dish.preferences().valueOf(discovered).orElseThrow(), "world");

        // Single-key combinator: .season(key, extractor)
        dish = chef.serve(Recipe.premade("CAFE").season(discovered, v -> v.substring(0, 2)));
        assertEquals(dish.value(), "CAFE");
        assertEquals(dish.preferences().valueOf(discovered).orElseThrow(), "CA");

        // Multi-key combinator: .season(Function<T, Preferences>)
        var intDish = chef.serve(Recipe.premade(42).season(v -> Preferences.of(discovered, v.toString(), extra, v)));
        assertEquals(intDish.value(), Integer.valueOf(42));
        assertEquals(intDish.preferences().valueOf(discovered).orElseThrow(), "42");
        assertEquals(intDish.preferences().valueOf(extra).orElseThrow(), Integer.valueOf(42));

        // Chained through then(): preferences visible to downstream recipe
        dish = chef.serve(
                Recipe.premade("ABC")
                        .season(discovered, v -> v)
                        .then(v -> Cookbook.deferred(prefs ->
                                Recipe.premade(prefs.valueOf(discovered).orElse("missing")))));
        assertEquals(dish.value(), "ABC");
        assertEquals(dish.preferences().valueOf(discovered).orElseThrow(), "ABC");

        // Nested season: both preferences accumulate
        intDish = chef.serve(
                Recipe.premade("A")
                        .season(discovered, v -> v)
                        .then(v -> Cookbook.season(99, Preferences.of(extra, 99))));
        assertEquals(intDish.value(), Integer.valueOf(99));
        assertEquals(intDish.preferences().valueOf(discovered).orElseThrow(), "A");
        assertEquals(intDish.preferences().valueOf(extra).orElseThrow(), Integer.valueOf(99));

        // With real I/O: season in a then() chain after card interaction
        var ioChef = new SousChef(MockBIBO.of("AABB9000", "9000"));
        var ioDish = ioChef.serve(
                Cookbook.send(new CommandAPDU(0x80, 0x50, 0x00, 0x00, 8))
                        .season(discovered, r -> HexUtils.bin2hex(r.getData()))
                        .and(Cookbook.send(new CommandAPDU(0x00, 0x00, 0x00, 0x00))));
        assertEquals(ioDish.preferences().valueOf(discovered).orElseThrow(), "AABB");

        // orElse propagates Seasoned
        dish = chef.serve(
                Recipe.premade("ok")
                        .season(discovered, v -> v)
                        .orElse(Recipe.premade("fallback")));
        assertEquals(dish.value(), "ok");
        assertEquals(dish.preferences().valueOf(discovered).orElseThrow(), "ok");

        // recover propagates Seasoned
        dish = chef.serve(
                Recipe.premade("ok")
                        .season(discovered, v -> v)
                        .recover(err -> Recipe.premade("recovered")));
        assertEquals(dish.value(), "ok");
        assertEquals(dish.preferences().valueOf(discovered).orElseThrow(), "ok");

        // MiseEnPlaceChef handles Seasoned identically
        var mepChef = new MiseEnPlaceChef();
        dish = mepChef.serve(
                Recipe.premade("test")
                        .season(discovered, v -> v)
                        .then(v -> Cookbook.deferred(prefs ->
                                Recipe.premade(prefs.valueOf(discovered).orElse("missing")))));
        assertEquals(dish.value(), "test");
        assertEquals(dish.preferences().valueOf(discovered).orElseThrow(), "test");
    }

    // === Ingredients size invariant ===

    @Test
    void ingredientsSizeInvariant() {
        var cmds = List.of(new CommandAPDU(0x00, 0xA4, 0x04, 0x00));
        var expected = List.of(ResponseAPDU.OK, ResponseAPDU.OK);
        // 1 command, 2 expected - must throw
        assertThrows(IllegalArgumentException.class,
                () -> new PreparationStep.Ingredients<>(cmds, expected, Cookbook.expect(0x9000)));
    }
}
