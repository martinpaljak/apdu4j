// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
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
