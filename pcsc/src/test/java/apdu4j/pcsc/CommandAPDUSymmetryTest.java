// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import apdu4j.core.CommandAPDU;
import apdu4j.core.HexUtils;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

// Verify apdu4j CommandAPDU behaves identically to javax.smartcardio.CommandAPDU
public class CommandAPDUSymmetryTest {

    // Compare all accessor values between apdu4j and JDK CommandAPDU
    static void assertSymmetric(int cla, int ins, int p1, int p2) {
        var ours = new CommandAPDU(cla, ins, p1, p2);
        var jdk = new javax.smartcardio.CommandAPDU(cla, ins, p1, p2);
        assertSame(ours, jdk, "case 1: cla=%d ins=%d p1=%d p2=%d".formatted(cla, ins, p1, p2));
    }

    static void assertSymmetric(int cla, int ins, int p1, int p2, int ne) {
        var ours = new CommandAPDU(cla, ins, p1, p2, ne);
        var jdk = new javax.smartcardio.CommandAPDU(cla, ins, p1, p2, ne);
        assertSame(ours, jdk, "case 2: cla=%d ne=%d".formatted(cla, ne));
    }

    static void assertSymmetric(int cla, int ins, int p1, int p2, byte[] data) {
        var ours = new CommandAPDU(cla, ins, p1, p2, data);
        var jdk = new javax.smartcardio.CommandAPDU(cla, ins, p1, p2, data);
        assertSame(ours, jdk, "case 3: cla=%d nc=%d".formatted(cla, data.length));
    }

    static void assertSymmetric(int cla, int ins, int p1, int p2, byte[] data, int ne) {
        var ours = new CommandAPDU(cla, ins, p1, p2, data, ne);
        var jdk = new javax.smartcardio.CommandAPDU(cla, ins, p1, p2, data, ne);
        assertSame(ours, jdk, "case 4: cla=%d nc=%d ne=%d".formatted(cla, data.length, ne));
    }

    static void assertSame(CommandAPDU ours, javax.smartcardio.CommandAPDU jdk, String ctx) {
        assertEquals(ours.getCLA(), jdk.getCLA(), ctx + " getCLA");
        assertEquals(ours.getINS(), jdk.getINS(), ctx + " getINS");
        assertEquals(ours.getP1(), jdk.getP1(), ctx + " getP1");
        assertEquals(ours.getP2(), jdk.getP2(), ctx + " getP2");
        assertEquals(ours.getNc(), jdk.getNc(), ctx + " getNc");
        assertEquals(ours.getNe(), jdk.getNe(), ctx + " getNe");
        assertEquals(ours.getData(), jdk.getData(), ctx + " getData");
        assertEquals(ours.getBytes(), jdk.getBytes(), ctx + " getBytes");
    }

    // === Case 1: header only ===

    @Test
    void case1Standard() {
        assertSymmetric(0x00, 0xA4, 0x04, 0x00);
    }

    @Test
    void case1ProprietaryCLA() {
        assertSymmetric(0x80, 0xCA, 0x9F, 0x7F);
    }

    @Test
    void case1SignedByteCLA() {
        // (byte) 0x80 widens to -128; must produce CLA=0x80
        assertSymmetric((byte) 0x80, (byte) 0xE4, 0x02, 0x00);
    }

    @Test
    void case1AllSignedBytes() {
        // All header bytes as signed byte values
        assertSymmetric((byte) 0x84, (byte) 0xE2, (byte) 0x80, (byte) 0xFF);
    }

    // === Case 2: header + Le ===

    @Test
    void case2ShortLe() {
        assertSymmetric(0x80, 0xCA, 0x00, 0x00, 1);
        assertSymmetric(0x80, 0xCA, 0x00, 0x00, 255);
    }

    @Test
    void case2Le256Boundary() {
        // Ne=256 encodes as Le=0x00 (short form special)
        assertSymmetric(0x00, 0xCA, 0x00, 0x00, 256);
    }

    @Test
    void case2ExtendedLe() {
        assertSymmetric(0x00, 0xCA, 0x00, 0x00, 257);
        assertSymmetric(0x00, 0xCA, 0x00, 0x00, 65536);
    }

    @Test
    void case2SignedByteCLA() {
        assertSymmetric((byte) 0x80, 0x50, 0x00, 0x00, 256);
    }

    // === Case 3: header + data ===

    @Test
    void case3ShortData() {
        assertSymmetric(0x80, 0xE8, 0x00, 0x00, new byte[]{0x01, 0x02});
    }

    @Test
    void case3Max255() {
        assertSymmetric(0x80, 0xE8, 0x00, 0x00, new byte[255]);
    }

    @Test
    void case3ExtendedData() {
        assertSymmetric(0x00, 0xA4, 0x04, 0x00, new byte[256]);
    }

    @Test
    void case3SignedByteCLA() {
        assertSymmetric((byte) 0x80, (byte) 0xE8, 0x00, 0x00, new byte[]{0x42});
    }

    // === Case 4: header + data + Le ===

    @Test
    void case4Short() {
        var data = new byte[]{(byte) 0xA0, 0x00, 0x00, 0x00, 0x03};
        assertSymmetric(0x00, 0xA4, 0x04, 0x00, data, 256);
    }

    @Test
    void case4Extended() {
        var data = new byte[]{(byte) 0xA0, 0x00, 0x00, 0x00, 0x03};
        assertSymmetric(0x00, 0xA4, 0x04, 0x00, data, 257);
    }

    @Test
    void case4LargeDataForcesExtended() {
        assertSymmetric(0x00, 0xA4, 0x04, 0x00, new byte[256], 1);
    }

    @Test
    void case4SignedByteCLA() {
        // GP INSTALL [for load]: CLA=0x80 INS=0xE6
        assertSymmetric((byte) 0x80, (byte) 0xE6, 0x0C, 0x00, new byte[]{0x01, 0x02, 0x03}, 256);
    }

    // === Byte array round-trip ===

    @Test
    void byteArrayRoundTrip() {
        // Construct from bytes, verify identical parsing
        var hex = "80CA9F7F";
        var ours = new CommandAPDU(HexUtils.hex2bin(hex));
        var jdk = new javax.smartcardio.CommandAPDU(HexUtils.hex2bin(hex));
        assertSame(ours, jdk, "byte[] round-trip");
    }

    @Test
    void byteArrayExtendedRoundTrip() {
        // Case 4e via bytes
        var bytes = new byte[4 + 5 + 7]; // header + 00|Lc2|Le2 + 7 data bytes
        bytes[0] = (byte) 0x80;
        bytes[1] = (byte) 0xE6;
        bytes[2] = 0x0C;
        bytes[3] = 0x00;
        // extended: 00 00 07 (Lc=7)
        bytes[5] = 0x00;
        bytes[6] = 0x07;
        // 7 bytes data
        bytes[7] = 0x01;
        // Le=0x01 0x00 (=256)
        bytes[14] = 0x01;
        bytes[15] = 0x00;
        var ours = new CommandAPDU(bytes);
        var jdk = new javax.smartcardio.CommandAPDU(bytes);
        assertSame(ours, jdk, "extended byte[] round-trip");
    }
}
