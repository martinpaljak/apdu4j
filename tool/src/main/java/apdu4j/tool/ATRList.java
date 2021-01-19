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
package apdu4j.tool;

import apdu4j.core.HexUtils;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public final class ATRList {
    private final Map<String, String> map;

    public static ATRList from(InputStream in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Map<String, String> entries = new HashMap<>();
            List<String> lines = reader.lines().collect(Collectors.toList());
            String name = null;
            String desc = null;
            for (String line : lines) {
                if (line.length() == 0) {
                    if (name != null && desc != null) {
                        entries.put(name.replace(" ", ""), desc.trim().replace('\t', '\n'));
                    }
                    desc = "";
                } else if (line.charAt(0) == '\t') {
                    desc += line;
                } else if (line.charAt(0) == '#') {
                    continue;
                } else {
                    name = line;
                }
            }
            return new ATRList(entries);
        }
    }

    public static ATRList from(String path) throws IOException {
        final InputStream in;
        if (path.startsWith("http")) {
            in = new URL(path).openStream();
        } else {
            in = new FileInputStream(path);
        }
        return from(in);
    }

    private ATRList(Map<String, String> map) {
        this.map = map;
    }

    public Optional<Map.Entry<String, String>> match(byte[] atr) {
        String q = HexUtils.bin2hex(atr).toUpperCase();
        return map.entrySet().stream().filter(e -> q.matches(e.getKey())).findFirst();
    }

    // Does the same as https://github.com/LudovicRousseau/pcsc-tools/blob/master/ATR_analysis#L58
    public static Optional<String> locate() {
        String home = System.getProperty("user.home", "");
        Path[] paths = new Path[]{
                Paths.get(home, ".cache", ".smartcard_list.txt"),
                Paths.get(home, ".smartcard_list.txt"),
                Paths.get("/usr/local/pcsc/smartcard_list.txt"),
                Paths.get("/usr/share/pcsc/smartcard_list.txt"),
                Paths.get("/usr/local/share/pcsc/smartcard_list.txt")};

        return Arrays.stream(paths).filter(Files::exists).findFirst().map(Path::toString);
    }
}
