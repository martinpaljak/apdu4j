// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class DumpFormat {

    private DumpFormat() {
    }

    public record DumpData(List<String> comments, List<byte[]> commands, List<byte[]> responses) {
        public DumpData {
            comments = List.copyOf(comments);
            commands = List.copyOf(commands);
            responses = List.copyOf(responses);
        }

        public byte[] atr() {
            for (var c : comments) {
                if (c.startsWith("# ATR: ")) {
                    return HexUtils.hex2bin(c.substring("# ATR: ".length()).trim());
                }
            }
            throw new IllegalStateException("No ATR found in dump comments");
        }

        public String protocol() {
            for (var c : comments) {
                if (c.startsWith("# PROTOCOL: ")) {
                    return c.substring("# PROTOCOL: ".length()).trim();
                }
            }
            throw new IllegalStateException("No PROTOCOL found in dump comments");
        }
    }

    public static DumpData parse(InputStream in) {
        var comments = new ArrayList<String>();
        var hexLines = new ArrayList<String>();
        var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (var line : reader.lines().toList()) {
            var trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                comments.add(trimmed);
            } else {
                hexLines.add(trimmed);
            }
        }
        if (hexLines.isEmpty()) {
            throw new IllegalArgumentException("Empty dump: no hex data");
        }
        if (hexLines.size() % 2 != 0) {
            throw new IllegalArgumentException("Unpaired trailing command in dump");
        }
        var commands = new ArrayList<byte[]>();
        var responses = new ArrayList<byte[]>();
        for (var i = 0; i < hexLines.size(); i += 2) {
            commands.add(HexUtils.hex2bin(hexLines.get(i)));
            responses.add(HexUtils.hex2bin(hexLines.get(i + 1)));
        }
        return new DumpData(comments, commands, responses);
    }
}
