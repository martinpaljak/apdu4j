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
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.*;

// Usage examples that double as tests. Each test shows a realistic scenario.
public class ApduletteTest {

    // === Basic: send a raw APDU and inspect the response ===

    @Test
    void sendRawApdu() {
        var mock = MockBIBO.of("CAFE9000");
        var chef = new SousChef(mock);

        var recipe = Cookbook.raw(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, 256));
        var response = chef.cook(recipe, new Preferences());

        assertEquals(response.getSW(), 0x9000);
        assertEquals(HexUtils.bin2hex(response.getData()), "CAFE");
    }

    // === Basic: send raw bytes, get raw bytes back ===

    @Test
    void sendRawBytes() {
        var mock = MockBIBO.of("CAFE9000");
        var chef = new SousChef(mock);

        var result = chef.cook(Cookbook.raw(HexUtils.hex2bin("00B0000000")), new Preferences());
        assertEquals(HexUtils.bin2hex(result), "CAFE9000");
    }

    // === SELECT applet by AID, then read UID ===

    @Test
    void selectAppletThenReadUid() {
        var mock = MockBIBO.of("9000", "010203040506079000");
        var chef = new SousChef(mock);

        var recipe = Cookbook.selectFCI(HexUtils.hex2bin("A000000003"))
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
        var recipe = Cookbook.selectFCI(HexUtils.hex2bin("A000000003"))
                .map(fci -> fci.getData().length)
                .then(len -> Cookbook.raw(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, len)));

        var response = chef.cook(recipe, new Preferences());
        assertEquals(response.getSW(), 0x9000);
    }

    // === Transform result with map ===

    @Test
    void mapTransformsResult() {
        var mock = MockBIBO.of("DEADBEEF9000");
        var chef = new SousChef(mock);

        var recipe = Cookbook.raw(new CommandAPDU(0x00, 0xCA, 0x00, 0x00, 256))
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

        var recipe = Cookbook.selectFCI(HexUtils.hex2bin("A000000003"))
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

        var recipe = Cookbook.selectFCI(HexUtils.hex2bin("A000000151"))
                .orElse(Cookbook.selectFCI(HexUtils.hex2bin("A0000000030000")));

        var response = chef.cook(recipe, new Preferences());
        assertEquals(response.getSW(), 0x9000);
    }

    // === Recover with error inspection ===

    @Test
    void recoverInspectsErrorSW() {
        var mock = MockBIBO.of("6A82");
        var chef = new SousChef(mock);

        // Recover from error: wrap the SW into a synthetic response
        var recipe = Cookbook.selectFCI(HexUtils.hex2bin("A000000003"))
                .recover(err -> Recipe.premade(
                        new ResponseAPDU(new byte[]{(byte) (err.sw() >> 8), (byte) err.sw()})));

        var result = chef.cook(recipe, new Preferences());
        assertEquals(result.getSW(), 0x6A82);
    }

    // === SpoiledIngredient carries the status word ===

    @Test
    void spoiledIngredientCarriesSW() {
        var mock = MockBIBO.of("6A82");
        var chef = new SousChef(mock);

        try {
            chef.cook(Cookbook.selectFCI(HexUtils.hex2bin("A000000003")), new Preferences());
            fail("Expected SpoiledIngredient");
        } catch (SpoiledIngredient e) {
            assertEquals(e.sw(), 0x6A82);
        }
    }

    // === Validate short-circuits on bad data ===

    @Test(expectedExceptions = IllegalStateException.class)
    void validateRejectsBadValue() {
        var mock = MockBIBO.of("9000");
        var chef = new SousChef(mock);

        // Select succeeds but response data is empty - validation fails
        var recipe = Cookbook.selectFCI(HexUtils.hex2bin("A000000003"))
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
                responses -> {
                    var allOk = responses.stream().allMatch(r -> r.getSW() == 0x9000);
                    return allOk
                            ? new Verdict.Ready<>(responses.size())
                            : new Verdict.Error<>("STORE DATA failed", 0x6F00);
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
                responses -> {
                    var sid = HexUtils.bin2hex(responses.get(0).getData());
                    return new Verdict.NextStep<>(
                            p -> new PreparationStep.Premade<>(p.valueOf(sessionId).orElseThrow()),
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
                responses -> {
                    var sid = HexUtils.bin2hex(responses.get(0).getData());
                    return new Verdict.NextStep<>(
                            p -> new PreparationStep.Premade<>(p.valueOf(sessionId).orElseThrow()),
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

    @Test(expectedExceptions = SpoiledIngredient.class)
    void errorRecipeAlwaysFails() {
        var chef = new SousChef(MockBIBO.of());
        chef.cook(Recipe.error("impossible", 0x6F00), new Preferences());
    }

    // === Monadic composition: chain three operations ===

    @Test
    void threeStepPipeline() {
        // SELECT -> READ BINARY -> parse length
        var mock = MockBIBO.of("9000", "0102030405060708090A9000");
        var chef = new SousChef(mock);

        var recipe = Cookbook.selectFCI(HexUtils.hex2bin("A000000003"))
                .and(Cookbook.raw(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, 256)))
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

        var result = chef.cook(Cookbook.send(0x00, 0xA4, 0x04, 0x00), new Preferences());
        assertEquals(result.getSW(), 0x9000);
    }

    @Test(expectedExceptions = SpoiledIngredient.class)
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

    // === simple() defers APDU construction to Preferences ===

    @Test
    void simpleBuildsApduFromPreferences() {
        var le = Preference.of("le", Integer.class, 256, false);

        // Recipe adapts Le from Preferences at prepare-time
        var recipe = Cookbook.simple(
                prefs -> new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[]{(byte) 0xA0}, prefs.get(le)),
                Cookbook.expect_9000());

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
        var recipe = Cookbook.raw(cmd).then(r -> {
            if (r.getSW1() == 0x61) {
                return Cookbook.raw(new CommandAPDU(cmd.getCLA(), 0xC0, 0x00, 0x00, r.getSW2()));
            }
            return Recipe.premade(r);
        });

        var result = chef.cook(recipe, new Preferences());
        assertEquals(result.getSW(), 0x9000);
        assertEquals(HexUtils.bin2hex(result.getData()), "AABB");
    }
}
