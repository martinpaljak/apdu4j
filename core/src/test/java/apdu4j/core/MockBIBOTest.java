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

import java.io.ByteArrayInputStream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class MockBIBOTest {

    // === Positive: factory methods and chaining ===

    @Test
    void testDepletionThrows() {
        var mock = MockBIBO.of("9000");
        mock.transceive(HexUtils.hex2bin("00A40400")); // consumes the one response
        assertThrows(BIBOException.class, () -> mock.transceive(HexUtils.hex2bin("00A40400")));
    }

    @Test
    void testCommandMismatchThrows() {
        var mock = MockBIBO.with("00A40400", "9000");
        assertThrows(BIBOException.class, () -> mock.transceive(HexUtils.hex2bin("00CA0000")));
    }

    @Test
    void testCommandMatchSucceeds() {
        var mock = MockBIBO.with("00A40400", "9000");
        var result = mock.transceive(HexUtils.hex2bin("00A40400"));
        assertEquals(result, HexUtils.hex2bin("9000"));
    }

    @Test
    void testChainedResponses() {
        var mock = MockBIBO.of("9000", "6A88");
        assertEquals(mock.transceive(HexUtils.hex2bin("00A40400")), HexUtils.hex2bin("9000"));
        assertEquals(mock.transceive(HexUtils.hex2bin("00CA0000")), HexUtils.hex2bin("6A88"));
        assertThrows(BIBOException.class, () -> mock.transceive(HexUtils.hex2bin("00000000")));
    }

    @Test
    void testThenChaining() {
        var mock = MockBIBO.with("00A40400", "9000").then("00CA0000", "6A88");
        assertEquals(mock.transceive(HexUtils.hex2bin("00A40400")), HexUtils.hex2bin("9000"));
        assertEquals(mock.transceive(HexUtils.hex2bin("00CA0000")), HexUtils.hex2bin("6A88"));
    }

    @Test
    void testFromDumpStream() {
        var input = "# ATR: 3B00\n# PROTOCOL: T=1\n#\n00A40400\n9000\n00CA0000\n6A88\n";
        var mock = MockBIBO.fromDump(new ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(mock.transceive(HexUtils.hex2bin("00A40400")), HexUtils.hex2bin("9000"));
        assertEquals(mock.transceive(HexUtils.hex2bin("00CA0000")), HexUtils.hex2bin("6A88"));
        assertThrows(BIBOException.class, () -> mock.transceive(HexUtils.hex2bin("00000000")));
    }

    @Test
    void testThenResponseOnly() {
        var mock = MockBIBO.with("00A40400", "9000").then("6A88");
        assertEquals(mock.transceive(HexUtils.hex2bin("00A40400")), HexUtils.hex2bin("9000"));
        assertEquals(mock.transceive(HexUtils.hex2bin("00CA0000")), HexUtils.hex2bin("6A88"));
    }

    // === API contracts: verification and loading ===

    @Test
    void testClosedThrows() {
        var mock = MockBIBO.of("9000");
        mock.close();
        assertThrows(BIBOException.class, () -> mock.transceive(HexUtils.hex2bin("00A40400")));
    }

    @Test
    void testFromDumpVerifiesCommands() {
        var input = "00A40400\n9000\n";
        var mock = MockBIBO.fromDump(new ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(BIBOException.class, () -> mock.transceive(HexUtils.hex2bin("00CA0000")));
    }

    // === Skipping mode ===

    @Test
    void testSkippingFindsMatch() {
        var mock = MockBIBO.with("00A40400", "9000").then("80500000", "0011").skipping();
        // Skip past SELECT, find INITIALIZE UPDATE
        assertEquals(mock.transceive(HexUtils.hex2bin("80500000")), HexUtils.hex2bin("0011"));
    }

    @Test
    void testSkippingNoMatchThrows() {
        var mock = MockBIBO.with("00A40400", "9000").then("80500000", "0011").skipping();
        assertThrows(BIBOException.class, () -> mock.transceive(HexUtils.hex2bin("DEADBEEF")));
    }

    @Test
    void testSkippingSequentialAfterSkip() {
        var mock = MockBIBO.with("00A40400", "9000").then("80500000", "0011").then("84820100", "0022").skipping();
        assertEquals(mock.transceive(HexUtils.hex2bin("80500000")), HexUtils.hex2bin("0011"));
        assertEquals(mock.transceive(HexUtils.hex2bin("84820100")), HexUtils.hex2bin("0022"));
    }

    @Test
    void testSkippingRejectsNullCommand() {
        var mock = MockBIBO.of("9000");
        assertThrows(IllegalStateException.class, mock::skipping);
    }

    @Test
    void testSkippingDepletedAfterFailedMatch() {
        var mock = MockBIBO.with("00A40400", "9000").skipping();
        assertThrows(BIBOException.class, () -> mock.transceive(HexUtils.hex2bin("DEADBEEF")));
        assertThrows(BIBOException.class, () -> mock.transceive(HexUtils.hex2bin("00A40400")));
    }
}
