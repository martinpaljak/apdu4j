/*
 * Copyright (c) 2016-present Martin Paljak <martin@martinpaljak.net>
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

import java.util.HexFormat;

public final class HexUtils {
    private HexUtils() {
    }

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    public static byte[] hex2bin(final String hex) {
        return HEX.parseHex(hex);
    }

    public static String bin2hex(final byte[] bin) {
        return HEX.formatHex(bin);
    }

    public static byte[] stringToBin(String s) {
        s = s.toUpperCase().replace(" ", "").replace(":", "").replace(",", "");
        s = s.replace("(BYTE)", "").replace("0X", "");
        s = s.replace("\n", "").replace("\t", "").replace("\r", "");
        return HEX.parseHex(s);
    }
}
