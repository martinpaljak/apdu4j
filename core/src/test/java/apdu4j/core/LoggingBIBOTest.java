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

import java.util.ArrayList;

import static org.testng.Assert.*;

public class LoggingBIBOTest {

    // === APDU case formatting ===

    @Test
    void testApduCaseLogFormatting() {
        // Case 1: header only
        var lines = new ArrayList<String>();
        var mock = MockBIBO.of("9000");
        var logging = LoggingBIBO.wrap(mock, lines::add);
        logging.transceive(HexUtils.hex2bin("00A40400"));
        assertEquals(lines.get(0), ">> 00A40400");
        assertTrue(lines.get(1).startsWith("<< 9000 ("));
        assertTrue(lines.get(1).endsWith("ms)"));

        // Case 2: Le only
        lines.clear();
        logging = LoggingBIBO.wrap(MockBIBO.of("AABB9000"), lines::add);
        logging.transceive(HexUtils.hex2bin("00CA000000"));
        assertEquals(lines.get(0), ">> 00CA0000 00");
        assertTrue(lines.get(1).startsWith("<< AABB 9000 ("));

        // Case 3: data, no Le
        lines.clear();
        logging = LoggingBIBO.wrap(MockBIBO.of("9000"), lines::add);
        logging.transceive(HexUtils.hex2bin("00A4040007A0000000628101"));
        assertEquals(lines.get(0), ">> 00A40400 07 A0000000628101");

        // Case 4: data + Le
        lines.clear();
        logging = LoggingBIBO.wrap(MockBIBO.of("AABB9000"), lines::add);
        logging.transceive(HexUtils.hex2bin("00A4040007A000000062810100"));
        assertEquals(lines.get(0), ">> 00A40400 07 A0000000628101 00");
        assertTrue(lines.get(1).startsWith("<< AABB 9000 ("));
    }

    // === Error and edge cases ===

    @Test
    void testErrorLogging() {
        var lines = new ArrayList<String>();
        var mock = MockBIBO.throwing();
        var logging = LoggingBIBO.wrap(mock, lines::add);
        assertThrows(BIBOException.class, () -> logging.transceive(HexUtils.hex2bin("00A40400")));
        assertEquals(lines.size(), 2);
        assertEquals(lines.get(0), ">> 00A40400");
        assertTrue(lines.get(1).startsWith("<< [error] MockBIBO: configured to throw ("));
    }

    @Test
    void testMalformedAPDU() {
        var lines = new ArrayList<String>();
        var mock = MockBIBO.of("9000");
        var logging = LoggingBIBO.wrap(mock, lines::add);
        logging.transceive(HexUtils.hex2bin("AABB"));
        assertEquals(lines.get(0), ">> AABB [malformed]");
    }
}
