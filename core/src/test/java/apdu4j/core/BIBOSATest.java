// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

import apdu4j.prefs.Preference;
import apdu4j.prefs.Preferences;
import org.testng.annotations.Test;

import java.util.function.Function;

import static org.testng.Assert.*;

public class BIBOSATest {

    static final Preference.Default<String> KEY = Preference.of("key", String.class, "", false);
    static final Preference.Default<String> BLOCK_SIZE = Preference.of("blockSize", String.class, "0", false);
    static final Preference.Default<String> A = Preference.of("a", String.class, "", false);
    static final Preference.Default<String> B = Preference.of("b", String.class, "", false);
    static final Preference.Default<String> TEST = Preference.of("test", String.class, "", false);
    static final Preference.Default<String> WRAPPED = Preference.of("wrapped", String.class, "", false);
    static final Preference.Default<Boolean> USE_SM = Preference.of("useSM", Boolean.class, false, false);
    static final Preference.Default<String> PROTOCOL = Preference.of("protocol", String.class, "none", false);

    @Test
    void testDelegatesTransceive() {
        var mock = MockBIBO.of("9000");
        var stack = new BIBOSA(mock);
        var result = stack.transceive(HexUtils.hex2bin("00A40400"));
        assertEquals(result, HexUtils.hex2bin("9000"));
    }

    @Test
    void testDelegatesClose() {
        var mock = MockBIBO.of();
        var stack = new BIBOSA(mock);
        stack.close();
        assertThrows(BIBOException.class, () -> mock.transceive(new byte[]{0}));
    }

    @Test
    void testEmptyPreferencesByDefault() {
        var stack = new BIBOSA(MockBIBO.of());
        assertTrue(stack.preferences().isEmpty());
    }

    @Test
    void testThenFunctionPreservesPreferences() {
        var prefs = new Preferences().with(KEY, "value");
        var stack = new BIBOSA(MockBIBO.of("9000"), prefs)
                .then(GetResponseWrapper::wrap);
        assertEquals(stack.preferences().get(KEY), "value");
    }

    @Test
    void testThenMiddlewareAddsPreferences() {
        BIBOMiddleware mw = s -> new BIBOSA(s.bibo(), s.preferences().with(BLOCK_SIZE, "231"));
        var stack = new BIBOSA(MockBIBO.of()).then(mw);
        assertEquals(stack.preferences().get(BLOCK_SIZE), "231");
    }

    @Test
    void testMiddlewareChainAccumulatesPreferences() {
        BIBOMiddleware first = s -> new BIBOSA(s.bibo(), s.preferences().with(A, "1"));
        BIBOMiddleware second = s -> new BIBOSA(s.bibo(), s.preferences().with(B, "2"));
        var stack = new BIBOSA(MockBIBO.of()).then(first).then(second);
        assertEquals(stack.preferences().get(A), "1");
        assertEquals(stack.preferences().get(B), "2");
    }

    @Test
    void testPreferencesAreImmutable() {
        var prefs = new Preferences().with(KEY, "original");
        var stack = new BIBOSA(MockBIBO.of(), prefs);
        assertEquals(stack.preferences().get(KEY), "original");
    }

    @Test
    void testUsableAsBIBO() {
        BIBO bibo = new BIBOSA(MockBIBO.of("9000"));
        assertEquals(bibo.transceive(HexUtils.hex2bin("00A40400")), HexUtils.hex2bin("9000"));
    }

    @Test
    void testExistingWrappersComposeViaFunction() {
        var mock = MockBIBO.of("AA6102", "BBCC9000");
        var prefs = new Preferences().with(TEST, "yes");
        var stack = new BIBOSA(mock, prefs)
                .then(GetResponseWrapper::wrap);
        var result = stack.transceive(HexUtils.hex2bin("00A40400"));
        assertEquals(result, HexUtils.hex2bin("AABBCC9000"));
        assertEquals(stack.preferences().get(TEST), "yes");
    }

    @Test
    void testFactoryReadsPreferencesToSelectMiddleware() {
        BIBOMiddleware smMiddleware = s -> new BIBOSA(s.bibo(), s.preferences().with(PROTOCOL, "SM"));
        var stack = new BIBOSA(MockBIBO.of(), new Preferences().with(USE_SM, true))
                .adapt(prefs -> prefs.get(USE_SM) ? smMiddleware : BIBOMiddleware.identity());
        assertEquals(stack.preferences().get(PROTOCOL), "SM");
    }

    @Test
    void testFactoryReturnsIdentityOnCondition() {
        var stack = new BIBOSA(MockBIBO.of(), new Preferences().with(USE_SM, false))
                .adapt(prefs -> prefs.get(USE_SM)
                        ? s -> new BIBOSA(s.bibo(), s.preferences().with(PROTOCOL, "SM"))
                        : BIBOMiddleware.identity());
        assertEquals(stack.preferences().get(PROTOCOL), "none");
        assertFalse(stack.preferences().valueOf(PROTOCOL).isPresent());
    }

    @Test
    void testFactorySelectedMiddlewareAddsPreferences() {
        BIBOMiddleware first = s -> new BIBOSA(s.bibo(), s.preferences().with(A, "SCP03"));
        var stack = new BIBOSA(MockBIBO.of())
                .then(first)
                .adapt(prefs -> {
                    var scp = prefs.get(A);
                    return s -> new BIBOSA(s.bibo(), s.preferences().with(B, "wrapped-by-" + scp));
                });
        assertEquals(stack.preferences().get(A), "SCP03");
        assertEquals(stack.preferences().get(B), "wrapped-by-SCP03");
    }

    @Test
    void testIdentityMiddlewareIsNoOp() {
        var prefs = new Preferences().with(KEY, "preserved");
        var stack = new BIBOSA(MockBIBO.of("9000"), prefs).then(BIBOMiddleware.identity());
        assertEquals(stack.preferences().get(KEY), "preserved");
        assertEquals(stack.transceive(HexUtils.hex2bin("00A40400")), HexUtils.hex2bin("9000"));
    }

    @Test
    void testMiddlewareCanWrapBIBO() {
        BIBOMiddleware mw = s -> new BIBOSA(GetResponseWrapper.wrap(s.bibo()), s.preferences().with(WRAPPED, "true"));
        var mock = MockBIBO.of("AA6102", "BBCC9000");
        var stack = new BIBOSA(mock).then(mw);
        var result = stack.transceive(HexUtils.hex2bin("00A40400"));
        assertEquals(result, HexUtils.hex2bin("AABBCC9000"));
        assertEquals(stack.preferences().get(WRAPPED), "true");
    }

    @Test
    void testCompose() {
        // Empty compose is a no-op and preserves preferences
        var prefs = Preferences.of(KEY, "kept");
        var noop = new BIBOSA(MockBIBO.of("9000"), prefs).compose();
        assertEquals(noop.transceive(HexUtils.hex2bin("00A40400")), HexUtils.hex2bin("9000"));
        assertEquals(noop.preferences().get(KEY), "kept");

        // Leftmost = outermost: outbound APDU traverses wrappers left-to-right.
        // tagA appends 0xAA, tagB appends 0xBB. compose(tagA, tagB) means tagA wraps
        // tagB(transport), so transceive(X) goes tagA -> tagB -> transport, and the
        // transport sees X||AA||BB.
        var seen = new byte[1][];
        BIBO sink = cmd -> { seen[0] = cmd; return new byte[]{(byte) 0x90, 0x00}; };
        Function<BIBO, BIBO> tagA = tagger((byte) 0xAA);
        Function<BIBO, BIBO> tagB = tagger((byte) 0xBB);
        new BIBOSA(sink, prefs).compose(tagA, tagB).transceive(new byte[]{0x01});
        assertEquals(seen[0], new byte[]{0x01, (byte) 0xAA, (byte) 0xBB});
    }

    private static Function<BIBO, BIBO> tagger(byte tag) {
        return inner -> cmd -> {
            var out = new byte[cmd.length + 1];
            System.arraycopy(cmd, 0, out, 0, cmd.length);
            out[cmd.length] = tag;
            return inner.transceive(out);
        };
    }
}
