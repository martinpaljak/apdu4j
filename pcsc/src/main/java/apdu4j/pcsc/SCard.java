/*
 * Copyright (c) 2014-present Martin Paljak <martin@martinpaljak.net>
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
import java.util.regex.Pattern;

// PC/SC constants and helpers.
public final class SCard {
    private SCard() {
    }

    public enum Disconnect {
        RESET,   // SCARD_RESET_CARD (default)
        LEAVE,   // SCARD_LEAVE_CARD
        UNPOWER  // SCARD_UNPOWER_CARD
    }

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
    public static final String SCARD_E_INVALID_HANDLE = "SCARD_E_INVALID_HANDLE";
    public static final String SCARD_E_UNKNOWN_READER = "SCARD_E_UNKNOWN_READER";
    public static final String SCARD_E_READER_UNAVAILABLE = "SCARD_E_READER_UNAVAILABLE";

    public static int CARD_CTL_CODE(int c) {
        var os = System.getProperty("os.name", "unknown").toLowerCase();
        if (os.contains("windows")) {
            return 0x31 << 16 | c << 2;
        } else {
            return 0x42000000 + c;
        }
    }

    private static final Pattern SCARD_PATTERN = Pattern.compile("SCARD_\\w+");

    private static Optional<String> getscard(String s) {
        if (s == null) {
            return Optional.empty();
        }
        var m = SCARD_PATTERN.matcher(s);
        if (m.find()) {
            return Optional.ofNullable(m.group());
        }
        return Optional.empty();
    }

    // Given an instance of some Exception from a PC/SC system
    // return a meaningful PC/SC error name, if found in any of the exception messages.
    public static String getExceptionMessage(Throwable e) {
        return getPCSCError(e).orElse(e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    public static Optional<String> getPCSCError(Throwable e) {
        while (e != null) {
            var m = getscard(e.getMessage());
            if (m.isPresent()) {
                return m;
            }
            e = e.getCause();
        }
        return Optional.empty();
    }
}
