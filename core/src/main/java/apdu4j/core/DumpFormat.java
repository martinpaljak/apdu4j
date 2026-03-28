/*
 * Copyright (c) 2026-present Martin Paljak <martin@martinpaljak.net>
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
