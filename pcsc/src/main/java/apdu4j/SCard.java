/*
 * Copyright (c) 2014-2020 Martin Paljak
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
package apdu4j;

// Random SCard interface constants.
public final class SCard {
    public static final String SCARD_E_SHARING_VIOLATION = "SCARD_E_SHARING_VIOLATION";
    public static final String SCARD_E_NO_READERS_AVAILABLE = "SCARD_E_NO_READERS_AVAILABLE";
    public static final String SCARD_E_NOT_TRANSACTED = "SCARD_E_NOT_TRANSACTED";
    public static final String SCARD_E_NO_SMARTCARD = "SCARD_E_NO_SMARTCARD";
    public static final String SCARD_W_UNPOWERED_CARD = "SCARD_W_UNPOWERED_CARD";
    public static final String SCARD_E_UNSUPPORTED_FEATURE = "SCARD_E_UNSUPPORTED_FEATURE";
    public static final String SCARD_E_TIMEOUT = "SCARD_E_TIMEOUT";

    public static int CARD_CTL_CODE(int c) {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        if (os.indexOf("windows") != -1) {
            return 0x31 << 16 | c << 2;
        } else {
            return 0x42000000 + c;
        }
    }
}
