/*
 * Copyright (c) 2014-present Martin Paljak
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
package apdu4j.pcsc;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Random SCard interface constants.
public final class SCard {
    public static final String SCARD_E_SHARING_VIOLATION = "SCARD_E_SHARING_VIOLATION";
    public static final String SCARD_E_NO_READERS_AVAILABLE = "SCARD_E_NO_READERS_AVAILABLE";
    public static final String SCARD_E_NOT_TRANSACTED = "SCARD_E_NOT_TRANSACTED";
    public static final String SCARD_E_NO_SMARTCARD = "SCARD_E_NO_SMARTCARD";
    public static final String SCARD_E_NO_SERVICE = "SCARD_E_NO_SERVICE";
    public static final String SCARD_E_SERVICE_STOPPED = "SCARD_E_SERVICE_STOPPED";
    public static final String SCARD_W_UNPOWERED_CARD = "SCARD_W_UNPOWERED_CARD";
    public static final String SCARD_W_REMOVED_CARD = "SCARD_W_REMOVED_CARD";
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

    public static Optional<String> getscard(String s) {
        if (s == null)
            return Optional.empty();
        Pattern p = Pattern.compile("SCARD_\\w+");
        Matcher m = p.matcher(s);
        if (m.find()) {
            return Optional.ofNullable(m.group());
        }
        return Optional.empty();
    }

    // Given an instance of some Exception from a PC/SC system with JNA
    // return a meaningful PC/SC error name.
    public static String getExceptionMessage(Throwable e) {
        return getPCSCError(e).orElse(e.getMessage());
    }

    public static Optional<String> getPCSCError(Throwable e) {
        while (e != null) {
            Optional<String> m = getscard(e.getMessage());
            if (m.isPresent())
                return m;
            e = e.getCause();
        }
        return Optional.empty();
    }
}
