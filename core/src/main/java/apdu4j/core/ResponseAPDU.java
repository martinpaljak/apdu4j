/*
 * Copyright (c) 2019 Martin Paljak
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

public class ResponseAPDU {
    final byte[] apdu;

    public ResponseAPDU(byte[] bytes) {
        if (bytes.length < 2) {
            throw new IllegalArgumentException("APDU must be at least 2 bytes!");
        }
        this.apdu = bytes.clone();
    }

    public int getSW1() {
        return apdu[apdu.length - 2] & 0xff;
    }

    public int getSW2() {
        return apdu[apdu.length - 1] & 0xff;
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
}
