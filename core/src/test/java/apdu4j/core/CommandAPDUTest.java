// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

import org.testng.annotations.Test;

import java.nio.ByteBuffer;
import java.util.HexFormat;

import static org.testng.Assert.*;

public class CommandAPDUTest {

    static final HexFormat hex = HexFormat.of();

    // SELECT AID: CLA=00 INS=A4 P1=04 P2=00 Lc=07 data=A0000000628101 Le=00
    static final byte[] C1 = hex.parseHex("00A4040007A000000062810100");

    // === API contract: all constructor forms produce equivalent APDUs ===

    @Test
    void testConstructorEquivalence() {
        var fromBytes = new CommandAPDU(C1);
        var data = fromBytes.getData();
        int cla = 0x00;
        int ins = 0xA4;
        int p1 = 0x04;
        int p2 = 0x00;
        int nc = 7;
        int ne = fromBytes.getNe();

        // All 9 constructor forms produce the same header
        var all = new CommandAPDU[]{
                fromBytes,
                new CommandAPDU(cla, ins, p1, p2),
                new CommandAPDU(cla, ins, p1, p2, data),
                new CommandAPDU(cla, ins, p1, p2, data, ne),
                new CommandAPDU(cla, ins, p1, p2, ne),
                new CommandAPDU(ByteBuffer.wrap(C1)),
                new CommandAPDU(C1, 0, C1.length),
                new CommandAPDU(cla, ins, p1, p2, data, 0, nc),
                new CommandAPDU(cla, ins, p1, p2, data, 0, nc, ne),
        };
        for (var cm : all) {
            assertEquals(cm.getCLA(), cla);
            assertEquals(cm.getINS(), ins);
            assertEquals(cm.getP1(), p1);
            assertEquals(cm.getP2(), p2);
        }

        // String factory: hex, spaces, case-insensitive
        assertEquals(CommandAPDU.of("00A4040007A000000062810100"), fromBytes);
        assertEquals(CommandAPDU.of("00a4040007a000000062810100"), fromBytes);
        assertEquals(CommandAPDU.of("00A40400 07 A0000000628101 00"), fromBytes);

        // stringToBin handles copy-paste formats: colons, 0x prefix, Java source
        assertEquals(new CommandAPDU(HexUtils.stringToBin("00A4:04:00\n\t07\nA000000062810100")), fromBytes);
        assertEquals(new CommandAPDU(HexUtils.stringToBin(
                "(byte) 0x00, (byte) 0xA4, (byte) 0x04, (byte) 0x00, (byte) 0x07,\n" +
                        "(byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x62, (byte) 0x81, (byte) 0x01, (byte) 0x00")), fromBytes);

        // ByteBuffer: consumes remaining, respects position/limit
        var buf = ByteBuffer.wrap(C1);
        new CommandAPDU(buf);
        assertEquals(buf.remaining(), 0);

        byte[] padded = new byte[C1.length + 10];
        System.arraycopy(C1, 0, padded, 5, C1.length);
        assertEquals(new CommandAPDU(ByteBuffer.wrap(padded).position(5).limit(5 + C1.length)).getBytes(), C1);

        // Offset/length constructor isolates subarray
        padded[0] = (byte) 0xFF;
        assertEquals(new CommandAPDU(padded, 5, C1.length).getBytes(), C1);
    }

    // === API contract: ISO 7816-4 APDU case encoding ===

    @Test
    void testCase1HeaderOnly() {
        var cmd = new CommandAPDU(0x80, 0xCA, 0x9F, 0x7F);
        assertEquals(cmd.getBytes(), hex.parseHex("80CA9F7F"));
        assertEquals(cmd.getNc(), 0);
        assertEquals(cmd.getNe(), 0);
        assertEquals(cmd.getData(), new byte[0]);
    }

    @Test
    void testCase2LeBoundaries() {
        // ne=1: short Le
        assertEquals(new CommandAPDU(0x00, 0xCA, 0x00, 0x00, 1).getBytes(), hex.parseHex("00CA000001"));
        // ne=255: max short Le
        assertEquals(new CommandAPDU(0x00, 0xCA, 0x00, 0x00, 255).getBytes(), hex.parseHex("00CA0000FF"));
        // ne=256: Le=0x00 (special encoding)
        assertEquals(new CommandAPDU(0x00, 0xCA, 0x00, 0x00, 256).getBytes(), hex.parseHex("00CA000000"));
        assertEquals(new CommandAPDU(hex.parseHex("00CA000000")).getNe(), 256);
        // ne=257: crosses to extended form
        assertEquals(new CommandAPDU(0x00, 0xCA, 0x00, 0x00, 257).getBytes(), hex.parseHex("00CA0000000101"));
        // ne=65536: max extended Le=00 00 00
        assertEquals(new CommandAPDU(0x00, 0xCA, 0x00, 0x00, 65536).getBytes(), hex.parseHex("00CA0000000000"));
        assertEquals(new CommandAPDU(hex.parseHex("00CA0000000000")).getNe(), 65536);
        // ne=0: no Le -> case 1
        assertEquals(new CommandAPDU(0x00, 0xCA, 0x00, 0x00, 0).getBytes().length, 4);
    }

    @Test
    void testCase3LcBoundaries() {
        // Short Lc: 1 byte
        var cmd1 = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[]{0x42});
        assertEquals(cmd1.getBytes(), hex.parseHex("00A404000142"));
        assertEquals(cmd1.getNc(), 1);
        assertEquals(cmd1.getNe(), 0);

        // Short Lc: 255 bytes (max short)
        assertEquals(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[255]).getBytes().length, 4 + 1 + 255);
        // Extended Lc: 256 bytes (crosses to extended)
        assertEquals(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[256]).getBytes().length, 4 + 3 + 256);

        // null/empty data -> case 1
        assertEquals(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, (byte[]) null).getBytes().length, 4);
        assertEquals(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[0]).getBytes().length, 4);

        // data + ne=0 -> case 3 (not case 4)
        var cmd3 = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[]{0x42}, 0);
        assertEquals(cmd3.getNe(), 0);
        assertEquals(cmd3, cmd1);

        // Offset constructor
        byte[] big = {0, 0, 1, 2, 3, 0, 0};
        assertEquals(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, big, 2, 3).getData(), new byte[]{1, 2, 3});
    }

    @Test
    void testCase4ShortAndExtended() {
        var data = hex.parseHex("A0000000628101");

        // Case 4s: data<=255 and ne<=256
        var cmd4s = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, data, 256);
        assertEquals(cmd4s.getNc(), 7);
        assertEquals(cmd4s.getNe(), 256);
        assertEquals(cmd4s.getBytes()[cmd4s.getBytes().length - 1], (byte) 0x00); // Le=256 -> 0x00
        assertEquals(cmd4s.getData(), data);

        // Case 4e: ne>256 forces extended
        var cmd4e = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, data, 257);
        assertEquals(cmd4e.getNe(), 257);
        assertEquals(cmd4e.getBytes().length, 4 + 5 + 7); // header + 00|Lc2|Le2 + data

        // Case 4e: large data (>255) forces extended regardless of ne
        var bigData = new byte[256];
        var cmd4eBig = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, bigData, 1);
        assertEquals(cmd4eBig.getNc(), 256);
        assertEquals(cmd4eBig.getNe(), 1);
    }

    // === API contract: serialize -> parse round-trip ===

    @Test
    void testParseRoundTrip() {
        byte[] data = {0x01, 0x02, 0x03};
        CommandAPDU[] originals = {
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00),                // case 1
                new CommandAPDU(0x00, 0xCA, 0x00, 0x00, 256),           // case 2s boundary
                new CommandAPDU(0x00, 0xCA, 0x00, 0x00, 257),           // case 2e boundary
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, data),          // case 3s
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[300]), // case 3e
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, data, 100),    // case 4s
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, data, 1000),   // case 4e
        };
        for (var orig : originals) {
            var parsed = new CommandAPDU(orig.getBytes());
            assertEquals(parsed.getNc(), orig.getNc());
            assertEquals(parsed.getNe(), orig.getNe());
            assertEquals(parsed.getData(), orig.getData());
            assertEquals(parsed, orig);
        }
    }

    // === API contract: equals, hashCode, toString ===

    @Test
    void testEqualsHashCode() {
        var a = new CommandAPDU(C1);
        var b = new CommandAPDU(C1.clone());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new CommandAPDU(0x00, 0xA4, 0x04, 0x00));
        assertNotEquals(a, null);
    }

    @Test
    void testToString() {
        assertEquals(new CommandAPDU(0x80, 0xCA, 0x9F, 0x7F).toString(), "CommandAPDU[80CA9F7F]");
        assertEquals(new CommandAPDU(0x00, 0xCA, 0x00, 0x00, 255).toString(), "CommandAPDU[00CA0000 FF]");
        assertEquals(new CommandAPDU(0x00, 0xCA, 0x00, 0x00, 257).toString(), "CommandAPDU[00CA0000 000101]");
        assertEquals(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[]{0x42}).toString(), "CommandAPDU[00A40400 01 42]");
        assertEquals(new CommandAPDU(C1).toString(), "CommandAPDU[00A40400 07 A0000000628101 00]");
        var data256 = new byte[256];
        data256[0] = (byte) 0xAA;
        data256[255] = (byte) 0xBB;
        assertEquals(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, data256).toString(),
                "CommandAPDU[00A40400 000100 AA" + "00".repeat(254) + "BB]");
        assertEquals(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, data256, 257).toString(),
                "CommandAPDU[00A40400 000100 AA" + "00".repeat(254) + "BB 0101]");
    }

    // === Invalid inputs ===

    @Test
    void testInvalidByteArrayParsing() {
        // Too short
        assertThrows(IllegalArgumentException.class, () -> new CommandAPDU(new byte[3]));
        // Short form: Lc doesn't match data length
        assertThrows(IllegalArgumentException.class, () ->
                new CommandAPDU(new byte[]{0x00, (byte) 0xA4, 0x04, 0x00, 0x05, 0x01, 0x02}));
        // Extended form: truncated
        assertThrows(IllegalArgumentException.class, () ->
                new CommandAPDU(new byte[]{0x00, (byte) 0xA4, 0x04, 0x00, 0x00, 0x01}));
        // Extended form: Lc=0 but data present
        assertThrows(IllegalArgumentException.class, () ->
                new CommandAPDU(new byte[]{0x00, (byte) 0xA4, 0x04, 0x00, 0x00, 0x00, 0x00, 0x01}));
        // Extended form: Lc doesn't match data length
        assertThrows(IllegalArgumentException.class, () ->
                new CommandAPDU(new byte[]{0x00, (byte) 0xA4, 0x04, 0x00, 0x00, 0x00, 0x01, 0x42, 0x42}));
        // Invalid hex string
        assertThrows(IllegalArgumentException.class, () -> CommandAPDU.of("ZZZZ"));
        assertThrows(IllegalArgumentException.class, () -> CommandAPDU.of("00A")); // odd length
    }

    @Test
    void testInvalidConstructorArgs() {
        // Header byte range: -128..255 valid (signed + unsigned byte), outside throws
        assertThrows(IllegalArgumentException.class, () -> new CommandAPDU(0x1FF, 0xA4, 0x04, 0x00));
        assertThrows(IllegalArgumentException.class, () -> new CommandAPDU(0x00, 256, 0x04, 0x00));
        assertThrows(IllegalArgumentException.class, () -> new CommandAPDU(0x00, 0xA4, -129, 0x00));
        // Ne out of range
        assertThrows(IllegalArgumentException.class, () -> new CommandAPDU(0x00, 0xA4, 0x04, 0x00, -1));
        assertThrows(IllegalArgumentException.class, () -> new CommandAPDU(0x00, 0xA4, 0x04, 0x00, 65537));
        // Data too large
        assertThrows(IllegalArgumentException.class, () ->
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[65536], 0, 65536, 0));
        // Array bounds
        assertThrows(IndexOutOfBoundsException.class, () ->
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[10], -1, 5));
        assertThrows(IndexOutOfBoundsException.class, () ->
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[10], 5, 8));
        assertThrows(IndexOutOfBoundsException.class, () -> new CommandAPDU(C1, -1, C1.length));
        // Null array with non-zero length
        assertThrows(NullPointerException.class, () ->
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, null, 0, 1));
    }
}
