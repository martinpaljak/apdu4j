// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class ResponseAPDUTest {

    // === Positive: accessor coverage ===

    @Test
    void testStatusWord() {
        var r = ResponseAPDU.of(0x6A88);
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
