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

import java.io.ByteArrayOutputStream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class DumpingBIBOTest {

    // === Positive: output format coverage ===

    @Test
    void testDumpWritesHexPairs() {
        var out = new ByteArrayOutputStream();
        var mock = MockBIBO.of("9000");
        var dumping = DumpingBIBO.wrap(mock, out);
        dumping.transceive(HexUtils.hex2bin("00A40400"));
        dumping.close();
        var lines = out.toString().lines().toList();
        assertEquals(lines.get(0), "00A40400");
        assertTrue(lines.get(1).matches("# \\d+ms"));
        assertEquals(lines.get(2), "9000");
    }

    @Test
    void testDumpMultiplePairs() {
        var out = new ByteArrayOutputStream();
        var mock = MockBIBO.of("9000", "6A88");
        var dumping = DumpingBIBO.wrap(mock, out);
        dumping.transceive(HexUtils.hex2bin("00A40400"));
        dumping.transceive(HexUtils.hex2bin("00CA0000"));
        dumping.close();
        var lines = out.toString().lines().toList();
        assertEquals(lines.size(), 6); // 2 pairs x 3 lines each
        assertEquals(lines.get(0), "00A40400");
        assertEquals(lines.get(2), "9000");
        assertEquals(lines.get(3), "00CA0000");
        assertEquals(lines.get(5), "6A88");
    }

}
