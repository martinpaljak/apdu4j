// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

    // === Round-trip: writeHeader + DumpingBIBO -> parse -> fromDump reproduces params and script ===

    @Test
    void testHeaderRoundTrip() {
        var params = CardInfo.params(HexUtils.hex2bin("3B00"), "T=0");
        var out = new ByteArrayOutputStream();
        DumpFormat.writeHeader(out, params);
        var recorder = DumpingBIBO.wrap(MockBIBO.of("9000", "6A88"), out);
        recorder.transceive(HexUtils.hex2bin("00A40400"));
        recorder.transceive(HexUtils.hex2bin("00CA0000"));

        var session = MockBIBO.fromDump(stream(out.toString(StandardCharsets.UTF_8)));
        // Params survive the round-trip.
        assertEquals(session.preferences().valueOf(CardInfo.ATR).orElseThrow(), HexBytes.v("3B00"));
        assertEquals(session.preferences().valueOf(CardInfo.NEGOTIATED_PROTOCOL).orElseThrow(), "T=0");
        // The replay verifies every command in the byte script.
        assertEquals(session.transceive(HexUtils.hex2bin("00A40400")), HexUtils.hex2bin("9000"));
        assertEquals(session.transceive(HexUtils.hex2bin("00CA0000")), HexUtils.hex2bin("6A88"));
    }

}
