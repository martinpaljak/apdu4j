// SPDX-FileCopyrightText: 2016 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
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
