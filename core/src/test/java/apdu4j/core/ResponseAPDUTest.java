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
import static org.testng.Assert.assertThrows;

public class ResponseAPDUTest {

    // === Positive: accessor coverage ===

    @Test
    void testStatusWord() {
        var r = new ResponseAPDU(HexUtils.hex2bin("6A88"));
        assertEquals(r.getSW(), 0x6A88);
        assertEquals(r.getSW1(), 0x6A);
        assertEquals(r.getSW2(), 0x88);
        assertEquals(r.getData(), new byte[0]);
    }

    @Test
    void testGetDataWithPayload() {
        byte[] raw = HexUtils.hex2bin("AABB9000");
        var r = new ResponseAPDU(raw);
        assertEquals(r.getData(), HexUtils.hex2bin("AABB"));
        assertEquals(r.getSW(), 0x9000);
        assertEquals(r.getBytes(), raw);
        assertEquals(r.getSWBytes(), HexUtils.hex2bin("9000"));
    }

    // === API contracts ===

    @Test
    void testLengths() {
        assertThrows(IllegalArgumentException.class, () -> new ResponseAPDU(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new ResponseAPDU(new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> new ResponseAPDU(new byte[65539]));
    }

}
