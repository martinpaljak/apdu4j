// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;

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

    // --- T0Stripper ---

    @Test
    void testT0Stripper() {
        // Stripping: Case 4s on the API -> Case 3s on the wire
        assertEquals(T0Stripper.wrap(MockBIBO.with("00D6000004DEADBEEF", "9000"))
                .transceive(HexUtils.hex2bin("00D6000004DEADBEEF00")), HexUtils.hex2bin("9000"));

        // Pass-through (same reference, no copy) for Case 1/2s/3s and any extended form
        for (var hex : new String[]{"00A40400", "00B0000010", "00D6000004DEADBEEF", "00D60000000004DEADBEEF0010"}) {
            var in = HexUtils.hex2bin(hex);
            assertSame(T0Stripper.strip(in), in);
        }

        // Composed with GetResponseWrapper: strip outbound, chain 61 XX inbound
        var chain = MockBIBO.with("00A4040002AABB", "6102").then("00C0000002", "CCDD9000");
        assertEquals(new BIBOSA(chain).compose(GetResponseWrapper::wrap, T0Stripper::wrap)
                .transceive(HexUtils.hex2bin("00A4040002AABB00")), HexUtils.hex2bin("CCDD9000"));
    }

}
