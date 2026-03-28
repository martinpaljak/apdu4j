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
import java.nio.charset.StandardCharsets;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class DumpFormatTest {

    static ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    // === Positive: parsing coverage ===

    @Test
    void testParseSimple() {
        var dump = DumpFormat.parse(stream("00A40400\n9000\n"));
        assertEquals(dump.commands().size(), 1);
        assertEquals(dump.responses().size(), 1);
        assertEquals(dump.commands().get(0), HexUtils.hex2bin("00A40400"));
        assertEquals(dump.responses().get(0), HexUtils.hex2bin("9000"));
    }

    @Test
    void testParseWithComments() {
        var input = "# ATR: 3BF91300008131FE454A434F503234325232A3\n# PROTOCOL: T=1\n#\n00A40400\n# 42ms\n9000\n";
        var dump = DumpFormat.parse(stream(input));
        assertEquals(dump.comments().size(), 4); // includes "# 42ms" timing comment
        assertEquals(dump.commands().size(), 1);
        assertEquals(dump.atr(), HexUtils.hex2bin("3BF91300008131FE454A434F503234325232A3"));
        assertEquals(dump.protocol(), "T=1");
    }

    @Test
    void testParseSkipsBlankLines() {
        var input = "\n00A40400\n\n9000\n\n";
        var dump = DumpFormat.parse(stream(input));
        assertEquals(dump.commands().size(), 1);
    }

    @Test
    void testParseMultiplePairs() {
        var input = "00A40400\n9000\n00CA0000\n6A88\n";
        var dump = DumpFormat.parse(stream(input));
        assertEquals(dump.commands().size(), 2);
        assertEquals(dump.responses().size(), 2);
        assertEquals(dump.responses().get(1), HexUtils.hex2bin("6A88"));
    }

    // === API contracts ===

    @Test
    void testParseEmptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> DumpFormat.parse(stream("")));
        assertThrows(IllegalArgumentException.class, () -> DumpFormat.parse(stream("# comment only\n")));
    }

    @Test
    void testParseUnpairedThrows() {
        assertThrows(IllegalArgumentException.class, () -> DumpFormat.parse(stream("00A40400\n")));
    }

    @Test
    void testMissingAtrThrows() {
        var dump = DumpFormat.parse(stream("00A40400\n9000\n"));
        assertThrows(IllegalStateException.class, dump::atr);
    }

    @Test
    void testMissingProtocolThrows() {
        var dump = DumpFormat.parse(stream("00A40400\n9000\n"));
        assertThrows(IllegalStateException.class, dump::protocol);
    }

}
