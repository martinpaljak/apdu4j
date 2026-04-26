// SPDX-FileCopyrightText: 2014 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.tool;

import apdu4j.core.HexUtils;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public final class ATRList {
    private final Map<String, List<String>> map;
    private final String source;

    public static ATRList from(InputStream in, String path) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            var entries = new HashMap<String, List<String>>();

            List<String> allLines = reader.lines().toList();
            String atrmask = null;
            var desc = new ArrayList<String>();
            for (String line : allLines) {
                if (line.length() == 0) {
                    if (atrmask != null && desc != null) {
                        entries.put(atrmask.replace(" ", ""), desc);
                    }
                    desc = new ArrayList<>();
                } else if (line.charAt(0) == '\t') {
                    desc.add(line.trim());
                } else if (line.charAt(0) == '#') {
                    continue;
                } else {
                    atrmask = line;
                }
            }
            return new ATRList(entries, path);
        }
    }

    public static ATRList from(String path) throws IOException {
        final InputStream in;
        if (path.startsWith("http")) {
            in = new URL(path).openStream();
        } else if (Files.isRegularFile(Paths.get(path))) {
            in = new FileInputStream(path);
        } else {
            throw new IOException("Not a URL nor regular file: " + path);
        }
        return from(in, path);
    }

    private ATRList(Map<String, List<String>> map, String source) {
        this.map = map;
        this.source = source;
    }

    public Optional<Map.Entry<String, List<String>>> match(byte[] atr) {
        var q = HexUtils.bin2hex(atr).toUpperCase();
        return map.entrySet().stream().filter(e -> q.matches(e.getKey())).findFirst();
    }

    public Optional<String> getSource() {
        return Optional.ofNullable(source);
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
