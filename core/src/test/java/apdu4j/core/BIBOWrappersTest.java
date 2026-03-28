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
package apdu4j.core;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class BIBOWrappersTest {

    // === Positive: wrapper coverage ===

    // --- GetResponseWrapper ---

    @Test
    void testGetResponseSingleChain() {
        // First response: 1 byte data + 61 02 (2 more bytes available)
        // GET RESPONSE returns: 2 bytes data + 9000
        var mock = MockBIBO.of("AA6102", "BBCC9000");
        var wrapper = GetResponseWrapper.wrap(mock);
        var result = wrapper.transceive(HexUtils.hex2bin("00A40400"));
        assertEquals(result, HexUtils.hex2bin("AABBCC9000"));
    }

    @Test
    void testGetResponsePreservesCLA() {
        // CLA=80 should be preserved in GET RESPONSE command
        var mock = MockBIBO.with("80A40400", "AA6101")
                .then("80C00000" + "01", "BB9000");
        var wrapper = GetResponseWrapper.wrap(mock);
        var result = wrapper.transceive(HexUtils.hex2bin("80A40400"));
        assertEquals(result, HexUtils.hex2bin("AABB9000"));
    }

    @Test
    void testGetResponseMultiChain() {
        // 3 rounds: AA + 61 02, BB CC + 61 01, DD + 90 00
        var mock = MockBIBO.of("AA6102", "BBCC6101", "DD9000");
        var wrapper = GetResponseWrapper.wrap(mock);
        var result = wrapper.transceive(HexUtils.hex2bin("00A40400"));
        assertEquals(result, HexUtils.hex2bin("AABBCCDD9000"));
    }

    // --- GetMoreDataWrapper ---

    @Test
    void testGetMoreDataSingleChain() {
        var mock = MockBIBO.of("AA9F02", "BBCC9000");
        var wrapper = GetMoreDataWrapper.wrap(mock);
        var result = wrapper.transceive(HexUtils.hex2bin("00A40400"));
        assertEquals(result, HexUtils.hex2bin("AABBCC9000"));
    }

    // --- RetryWithRightLengthWrapper ---

    @Test
    void testRetryWithCorrectLe() {
        // First response: 6C 10 (wrong length, correct Le=0x10)
        // Retry with Le=0x10 returns success
        var mock = MockBIBO.of("6C10", "AABB9000");
        var wrapper = RetryWithRightLengthWrapper.wrap(mock);
        var result = wrapper.transceive(HexUtils.hex2bin("00CA000000"));
        assertEquals(result, HexUtils.hex2bin("AABB9000"));
    }

    @Test
    void testRetryWithCorrectLePreservesData() {
        // Case 4s: CLA INS P1 P2 Lc=04 DATA Le=00 -> 6C10 -> retry with Le=10, data preserved
        var mock = MockBIBO.with("00B0000004DEADBEEF00", "6C10")
                .then("00B0000004DEADBEEF10", "AABB9000");
        var wrapper = RetryWithRightLengthWrapper.wrap(mock);
        var result = wrapper.transceive(HexUtils.hex2bin("00B0000004DEADBEEF00"));
        assertEquals(result, HexUtils.hex2bin("AABB9000"));
    }

}
