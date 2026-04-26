// SPDX-FileCopyrightText: 2021 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
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
        return v(f.replace(" ", "").replace("%s", a.s()).replace("%l", "%02X".formatted(a.len())));
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
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        var bytesKey = (HexBytes) o;
        return Arrays.equals(v, bytesKey.v);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(v);
    }

    @Override
    public String toString() {
        return "HexBytes[" + s() + ']';
    }

    // Misc utils
    public static byte[] concatenate(byte[]... args) {
        var length = 0;
        var pos = 0;
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
