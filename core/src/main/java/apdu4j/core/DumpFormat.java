// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

import apdu4j.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class DumpFormat {
    private static final Logger logger = LoggerFactory.getLogger(DumpFormat.class);

    // Header prefixes shared by parse() and writeHeader().
    static final String ATR_COMMENT = "# ATR: ";
    static final String PROTOCOL_COMMENT = "# PROTOCOL: ";

    private DumpFormat() {
    }

    public record DumpData(List<String> comments, List<byte[]> commands, List<byte[]> responses) {
        public DumpData {
            comments = List.copyOf(comments);
            commands = List.copyOf(commands);
            responses = List.copyOf(responses);
        }

        public byte[] atr() {
            return HexUtils.hex2bin(comment(ATR_COMMENT)
                    .orElseThrow(() -> new IllegalStateException("No ATR found in dump comments")));
        }

        public String protocol() {
            return comment(PROTOCOL_COMMENT)
                    .orElseThrow(() -> new IllegalStateException("No PROTOCOL found in dump comments"));
        }

        // A dump missing its ATR or protocol header yields no params.
        public Preferences params() {
            var atr = comment(ATR_COMMENT);
            var protocol = comment(PROTOCOL_COMMENT);
            if (atr.isEmpty() || protocol.isEmpty()) {
                logger.warn("Dump lacks an ATR/PROTOCOL header; the replayed session carries no card params");
                return new Preferences();
            }
            return CardInfo.params(HexUtils.hex2bin(atr.get()), protocol.get());
        }

        private Optional<String> comment(String prefix) {
            for (var c : comments) {
                if (c.startsWith(prefix)) {
                    return Optional.of(c.substring(prefix.length()).trim());
                }
            }
            return Optional.empty();
        }
    }

    // Counterpart to DumpData scanning the header back.
    public static void writeHeader(OutputStream out, Preferences params) {
        var ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        params.valueOf(CardInfo.ATR).ifPresent(atr -> ps.println(ATR_COMMENT + atr.s()));
        params.valueOf(CardInfo.NEGOTIATED_PROTOCOL).ifPresent(protocol -> ps.println(PROTOCOL_COMMENT + protocol));
        ps.println("#");
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
