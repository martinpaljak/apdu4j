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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable command APDU following the structure defined in ISO/IEC 7816-4.
 * Supports both short and extended forms of length encoding for Ne and Nc.
 *
 * <p>Header bytes CLA, INS, P1, P2 must be in range 0..255.
 * Accessor methods return unsigned values.
 *
 * @see ResponseAPDU
 */
public record CommandAPDU(byte[] apdu) {

    // Compact canonical constructor -defensive clone + validate
    public CommandAPDU {
        apdu = apdu.clone();
        parseFields(apdu); // validate structure
    }

    public CommandAPDU(String hex) {
        this(HexUtils.hex2bin(hex.replace(" ", "")));
    }

    public CommandAPDU(byte[] bytes, int apduOffset, int apduLength) {
        this(checkedCopyOfRange(bytes, apduOffset, apduLength));
    }

    public CommandAPDU(ByteBuffer buf) {
        this(toByteArray(buf));
    }

    public CommandAPDU(int cla, int ins, int p1, int p2) {
        this(cla, ins, p1, p2, null, 0, 0, 0);
    }

    public CommandAPDU(int cla, int ins, int p1, int p2, int ne) {
        this(cla, ins, p1, p2, null, 0, 0, ne);
    }

    public CommandAPDU(int cla, int ins, int p1, int p2, byte[] data) {
        this(cla, ins, p1, p2, data, 0, data == null ? 0 : data.length, 0);
    }

    public CommandAPDU(int cla, int ins, int p1, int p2, byte[] data, int ne) {
        this(cla, ins, p1, p2, data, 0, data == null ? 0 : data.length, ne);
    }

    public CommandAPDU(int cla, int ins, int p1, int p2, byte[] data,
                       int dataOffset, int dataLength) {
        this(cla, ins, p1, p2, data, dataOffset, dataLength, 0);
    }

    public CommandAPDU(int cla, int ins, int p1, int p2, byte[] data,
                       int dataOffset, int dataLength, int ne) {
        this(buildApdu(cla, ins, p1, p2, data, dataOffset, dataLength, ne));
    }

    // === Accessors ===

    @Override
    public byte[] apdu() { // defensive clone; prefer getBytes()
        return apdu.clone();
    }

    public int getCLA() {
        return Byte.toUnsignedInt(apdu[0]);
    }

    public int getINS() {
        return Byte.toUnsignedInt(apdu[1]);
    }

    public int getP1() {
        return Byte.toUnsignedInt(apdu[2]);
    }

    public int getP2() {
        return Byte.toUnsignedInt(apdu[3]);
    }

    public int getNc() {
        return parseFields(apdu)[0];
    }

    public int getNe() {
        return parseFields(apdu)[1];
    }

    public byte[] getBytes() {
        return apdu.clone();
    }

    public byte[] getData() {
        int[] p = parseFields(apdu);
        return Arrays.copyOfRange(apdu, p[2], p[2] + p[0]);
    }

    // === formatting ===

    public String toLogString() {
        var sb = new StringBuilder();
        int[] fields = parseFields(apdu);
        var nc = fields[0];
        var ne = fields[1];
        var dataOffset = fields[2];
        // Header always first
        sb.append(HexUtils.bin2hex(Arrays.copyOfRange(apdu, 0, 4)));
        if (apdu.length == 4) {
            return sb.toString(); // case 1
        }
        // Length encoding: everything between header and data (or end if no data)
        if (nc > 0) {
            sb.append(' ').append(HexUtils.bin2hex(Arrays.copyOfRange(apdu, 4, dataOffset)));
            sb.append(' ').append(HexUtils.bin2hex(Arrays.copyOfRange(apdu, dataOffset, dataOffset + nc)));
            if (ne > 0) {
                sb.append(' ').append(HexUtils.bin2hex(Arrays.copyOfRange(apdu, dataOffset + nc, apdu.length)));
            }
        } else {
            // case 2s or 2e: Le encoding after header
            sb.append(' ').append(HexUtils.bin2hex(Arrays.copyOfRange(apdu, 4, apdu.length)));
        }
        return sb.toString();
    }

    // === equals / hashCode / toString ===

    @Override
    public boolean equals(Object obj) {
        return obj instanceof CommandAPDU other && Arrays.equals(this.apdu, other.apdu);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(apdu);
    }

    @Override
    public String toString() {
        return "CommandAPDU[" + toLogString() + "]";
    }

    // === Static helpers ===

    private static byte[] toByteArray(ByteBuffer buf) {
        var bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return bytes;
    }

    private static byte[] checkedCopyOfRange(byte[] src, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, src.length);
        return Arrays.copyOfRange(src, offset, offset + length);
    }

    // Le=0x00 encodes 256 (short) or Le=0x0000 encodes 65536 (extended)
    private static int decodeLe(int encoded, int zeroMeaning) {
        return encoded == 0 ? zeroMeaning : encoded;
    }

    /**
     * Parses APDU bytes into {nc, ne, dataOffset}.
     * <p>
     * case 1:  |CLA|INS|P1 |P2 |                                 len = 4
     * case 2s: |CLA|INS|P1 |P2 |LE |                             len = 5
     * case 3s: |CLA|INS|P1 |P2 |LC |...BODY...|                  len = 6..260
     * case 4s: |CLA|INS|P1 |P2 |LC |...BODY...|LE |              len = 7..261
     * case 2e: |CLA|INS|P1 |P2 |00 |LE1|LE2|                     len = 7
     * case 3e: |CLA|INS|P1 |P2 |00 |LC1|LC2|...BODY...|          len = 8..65542
     * case 4e: |CLA|INS|P1 |P2 |00 |LC1|LC2|...BODY...|LE1|LE2|  len =10..65544
     */
    // returns {nc, ne, dataOffset} parsed from encoded apdu bytes
    private static int[] parseFields(byte[] apdu) {
        if (apdu.length < 4) {
            throw new IllegalArgumentException("APDU too short: length=%d (minimum 4)".formatted(apdu.length));
        }
        // case 1: header only
        if (apdu.length == 4) {
            return new int[]{0, 0, 0};
        }
        // first length byte after header
        var l1 = Byte.toUnsignedInt(apdu[4]);
        // case 2s: header + single le byte
        if (apdu.length == 5) {
            return new int[]{0, decodeLe(l1, 256), 0};
        }
        // short form: l1 is lc
        if (l1 != 0) {
            if (apdu.length == 4 + 1 + l1) {
                return new int[]{l1, 0, 5}; // case 3s: data only
            } else if (apdu.length == 4 + 2 + l1) {
                return new int[]{l1, decodeLe(Byte.toUnsignedInt(apdu[apdu.length - 1]), 256), 5}; // case 4s: data + le
            } else {
                throw new IllegalArgumentException("APDU length mismatch: length=%d, Lc=%d".formatted(apdu.length, l1));
            }
        }
        // extended form: l1=0x00 signals 2-byte lc/le follows
        if (apdu.length < 7) {
            throw new IllegalArgumentException("APDU truncated: length=%d, Lc and Le incomplete".formatted(apdu.length));
        }
        var l2 = (Byte.toUnsignedInt(apdu[5]) << 8) | Byte.toUnsignedInt(apdu[6]);
        // case 2e: header + 00 + 2-byte le
        if (apdu.length == 7) {
            return new int[]{0, decodeLe(l2, 65536), 0};
        }
        // extended lc must not be zero
        if (l2 == 0) {
            throw new IllegalArgumentException("APDU length mismatch: length=%d, Lc=0 with unexpected data".formatted(apdu.length));
        }
        if (apdu.length == 4 + 3 + l2) {
            return new int[]{l2, 0, 7}; // case 3e: extended data only
        } else if (apdu.length == 4 + 5 + l2) {
            // case 4e: extended data + 2-byte le
            var leOfs = apdu.length - 2;
            var l3 = (Byte.toUnsignedInt(apdu[leOfs]) << 8) | Byte.toUnsignedInt(apdu[leOfs + 1]);
            return new int[]{l2, decodeLe(l3, 65536), 7};
        } else {
            throw new IllegalArgumentException("APDU length mismatch: length=%d, Lc=%d".formatted(apdu.length, l2));
        }
    }

    // Accept both signed (-128..127) and unsigned (0..255) byte ranges
    private static void checkByte(int value, String name) {
        if (value < -128 || value > 255) {
            throw new IllegalArgumentException("%s out of range: %d (must be -128..255)".formatted(name, value));
        }
    }

    private static byte[] buildApdu(int cla, int ins, int p1, int p2,
                                    byte[] data, int dataOffset, int dataLength, int ne) {
        checkByte(cla, "CLA");
        checkByte(ins, "INS");
        checkByte(p1, "P1");
        checkByte(p2, "P2");
        if (dataLength > 65535) {
            throw new IllegalArgumentException("Data too large: Nc=%d (maximum 65535)".formatted(dataLength));
        }
        if (ne < 0) {
            throw new IllegalArgumentException("Ne out of range: %d (minimum 0)".formatted(ne));
        }
        if (ne > 65536) {
            throw new IllegalArgumentException("Ne out of range: %d (maximum 65536)".formatted(ne));
        }
        if (dataLength > 0) {
            Objects.checkFromIndexSize(dataOffset, dataLength, data.length);
        }

        byte[] apdu;
        if (dataLength == 0) {
            // no command data
            if (ne == 0) {
                // case 1: header only
                apdu = new byte[4];
            } else if (ne <= 256) {
                // case 2s: 1-byte le, 256 encoded as 0x00
                apdu = new byte[5];
                apdu[4] = ne != 256 ? (byte) ne : 0;
            } else {
                // case 2e: 2-byte le after 0x00 marker, 65536 encoded as 0x0000
                apdu = new byte[7];
                if (ne != 65536) {
                    apdu[5] = (byte) (ne >> 8);
                    apdu[6] = (byte) ne;
                }
            }
        } else if (ne == 0) {
            // command data, no expected response length
            if (dataLength <= 255) {
                // case 3s: 1-byte lc + data
                apdu = new byte[4 + 1 + dataLength];
                apdu[4] = (byte) dataLength;
                System.arraycopy(data, dataOffset, apdu, 5, dataLength);
            } else {
                // case 3e: 0x00 marker + 2-byte lc + data
                apdu = new byte[4 + 3 + dataLength];
                apdu[5] = (byte) (dataLength >> 8);
                apdu[6] = (byte) dataLength;
                System.arraycopy(data, dataOffset, apdu, 7, dataLength);
            }
        } else {
            // command data + expected response length
            if ((dataLength <= 255) && (ne <= 256)) {
                // case 4s: 1-byte lc + data + 1-byte le
                apdu = new byte[4 + 2 + dataLength];
                apdu[4] = (byte) dataLength;
                System.arraycopy(data, dataOffset, apdu, 5, dataLength);
                apdu[apdu.length - 1] = ne != 256 ? (byte) ne : 0;
            } else {
                // case 4e: 0x00 marker + 2-byte lc + data + 2-byte le
                apdu = new byte[4 + 5 + dataLength];
                apdu[5] = (byte) (dataLength >> 8);
                apdu[6] = (byte) dataLength;
                System.arraycopy(data, dataOffset, apdu, 7, dataLength);
                if (ne != 65536) {
                    var leOfs = apdu.length - 2;
                    apdu[leOfs] = (byte) (ne >> 8);
                    apdu[leOfs + 1] = (byte) ne;
                }
            }
        }
        // write header last (array is zero-initialized)
        apdu[0] = (byte) cla;
        apdu[1] = (byte) ins;
        apdu[2] = (byte) p1;
        apdu[3] = (byte) p2;
        return apdu;
    }
}
