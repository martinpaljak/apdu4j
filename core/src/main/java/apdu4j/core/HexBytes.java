/*
 * Copyright (c) 2021-present Martin Paljak
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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

// Simple wrapper for byte[] represented as hex. Usable as map key or cmdline parser
public final class HexBytes {
    private final byte[] v;

    public HexBytes(String hex) {
        v = HexUtils.hex2bin(hex);
    }

    private HexBytes(byte[] v) {
        this.v = v.clone();
    }

    public static HexBytes valueOf(String s) {
        if (s.startsWith("|") && s.endsWith("|")) {
            return new HexBytes(s.substring(1, s.length() - 1).getBytes(StandardCharsets.UTF_8));
        }
        return new HexBytes(HexUtils.hex2bin(s));
    }

    public static HexBytes v(String s) {
        return valueOf(s);
    }

    public static HexBytes b(byte[] b) {
        return new HexBytes(b);
    }

    public static HexBytes f(String f, HexBytes a) {
        return v(f.replace(" ", "").replace("%s", a.s()).replace("%l", String.format("%02X", a.len())));
    }

    public int len() {
        return v.length;
    }

    public String s() {
        return HexUtils.bin2hex(v);
    }

    public byte[] value() {
        return v.clone();
    }

    public byte[] v() {
        return value();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HexBytes bytesKey = (HexBytes) o;
        return Arrays.equals(v, bytesKey.v);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(v);
    }


    @Override
    public String toString() {
        return "HexBytes[" + HexUtils.bin2hex(v) + ']';
    }

    // Misc utils
    public static byte[] concatenate(byte[]... args) {
        int length = 0, pos = 0;
        for (byte[] arg : args) {
            length += arg.length;
        }
        byte[] result = new byte[length];
        for (byte[] arg : args) {
            System.arraycopy(arg, 0, result, pos, arg.length);
            pos += arg.length;
        }
        return result;
    }
}
