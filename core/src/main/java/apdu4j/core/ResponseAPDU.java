/*
 * Copyright (c) 2019-present Martin Paljak <martin@martinpaljak.net>
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

import java.util.Arrays;

public record ResponseAPDU(byte[] apdu) {

    public static final ResponseAPDU OK = of(0x9000);

    // SW int to ResponseAPDU
    public static ResponseAPDU of(int sw) {
        return new ResponseAPDU(new byte[]{(byte) (sw >> 8), (byte) sw});
    }

    public static ResponseAPDU of(String hex) {
        return new ResponseAPDU(HexUtils.hex2bin(hex.replace(" ", "")));
    }

    private static final int MAX_LENGTH = 65538; // 65536 data + 2 SW

    public ResponseAPDU {
        if (apdu.length < 2 || apdu.length > MAX_LENGTH) {
            throw new IllegalArgumentException("Response APDU length must be 2..%d, got %d".formatted(MAX_LENGTH, apdu.length));
        }
        apdu = apdu.clone();
    }

    @Override
    public byte[] apdu() { // defensive clone; prefer getBytes()
        return apdu.clone();
    }

    public int getSW1() {
        return Byte.toUnsignedInt(apdu[apdu.length - 2]);
    }

    public int getSW2() {
        return Byte.toUnsignedInt(apdu[apdu.length - 1]);
    }

    public int getSW() {
        return (getSW1() << 8) | getSW2();
    }

    public byte[] getData() {
        return Arrays.copyOf(apdu, apdu.length - 2);
    }

    public byte[] getBytes() {
        return apdu.clone();
    }

    public byte[] getSWBytes() {
        return Arrays.copyOfRange(apdu, apdu.length - 2, apdu.length);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ResponseAPDU other && Arrays.equals(this.apdu, other.apdu);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(apdu);
    }

    @Override
    public String toString() {
        return "ResponseAPDU[" + toLogString() + "]";
    }

    public String toLogString() {
        if (apdu.length > 2) {
            return HexUtils.bin2hex(getData()) + " " + HexUtils.bin2hex(getSWBytes());
        }
        return HexUtils.bin2hex(apdu);
    }
}
