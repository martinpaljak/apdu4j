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
package apdu4j.bibosa;

import apdu4j.core.*;
import apdu4j.prefs.Preference;
import apdu4j.prefs.Preferences;
import org.testng.annotations.Test;

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
        // Preferences are immutable - the original and the stack's copy are the same object
        assertEquals(stack.preferences().get(KEY), "original");
    }

    @Test
    void testUsableAsBIBO() {
        BIBO bibo = new BIBOSA(MockBIBO.of("9000"));
        assertEquals(bibo.transceive(HexUtils.hex2bin("00A40400")), HexUtils.hex2bin("9000"));
    }

    @Test
    void testExistingWrappersComposeViaFunction() {
        // GetResponseWrapper chains 61xx, preferences survive
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
        // First layer sets protocol hint, factory reads it to select middleware
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
        // Middleware that wraps BIBO with GetResponseWrapper and adds a preference
        BIBOMiddleware mw = s -> new BIBOSA(GetResponseWrapper.wrap(s.bibo()), s.preferences().with(WRAPPED, "true"));
        var mock = MockBIBO.of("AA6102", "BBCC9000");
        var stack = new BIBOSA(mock).then(mw);
        var result = stack.transceive(HexUtils.hex2bin("00A40400"));
        assertEquals(result, HexUtils.hex2bin("AABBCC9000"));
        assertEquals(stack.preferences().get(WRAPPED), "true");
    }
}
