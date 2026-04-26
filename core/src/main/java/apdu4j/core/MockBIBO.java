// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;

// Test helper: queued command-response BIBO mock with optional command verification
public class MockBIBO implements BIBO {
    private final ArrayDeque<Pair> pairs;
    private final boolean skipping;
    private volatile boolean closed;

    private record Pair(byte[] command, byte[] response) {
    }

    private MockBIBO(ArrayDeque<Pair> pairs, boolean skipping) {
        this.pairs = pairs;
        this.skipping = skipping;
    }

    private MockBIBO(ArrayDeque<Pair> pairs) {
        this(pairs, false);
    }

    public static MockBIBO of() {
        return new MockBIBO(new ArrayDeque<>());
    }

    // Simple response mode - no command checking
    public static MockBIBO of(String... responses) {
        var q = new ArrayDeque<Pair>();
        for (var r : responses) {
            q.add(new Pair(null, HexUtils.hex2bin(r)));
        }
        return new MockBIBO(q);
    }

    public static MockBIBO of(byte[]... responses) {
        var q = new ArrayDeque<Pair>();
        for (var r : responses) {
            q.add(new Pair(null, r));
        }
        return new MockBIBO(q);
    }

    public static MockBIBO throwing() {
        var q = new ArrayDeque<Pair>();
        q.add(new Pair(null, null));
        return new MockBIBO(q);
    }

    // Build from dump data
    public static MockBIBO fromDump(InputStream in) {
        return fromDump(DumpFormat.parse(in));
    }

    public static MockBIBO fromDump(DumpFormat.DumpData dump) {
        var q = new ArrayDeque<Pair>();
        for (var i = 0; i < dump.commands().size(); i++) {
            q.add(new Pair(dump.commands().get(i), dump.responses().get(i)));
        }
        return new MockBIBO(q);
    }

    // Command-response verification mode
    public static MockBIBO with(String command, String response) {
        var q = new ArrayDeque<Pair>();
        q.add(new Pair(HexUtils.hex2bin(command), HexUtils.hex2bin(response)));
        return new MockBIBO(q);
    }

    public MockBIBO then(String command, String response) {
        var q = new ArrayDeque<>(pairs);
        q.add(new Pair(HexUtils.hex2bin(command), HexUtils.hex2bin(response)));
        return new MockBIBO(q);
    }

    public MockBIBO then(String response) {
        var q = new ArrayDeque<>(pairs);
        q.add(new Pair(null, HexUtils.hex2bin(response)));
        return new MockBIBO(q);
    }

    // Skipping mode: scan forward to find a matching command instead of failing on mismatch
    public MockBIBO skipping() {
        if (pairs.stream().anyMatch(p -> p.command == null)) {
            throw new IllegalStateException("MockBIBO: skipping requires all pairs to have commands");
        }
        return new MockBIBO(new ArrayDeque<>(pairs), true);
    }

    public static MockBIBO skipping(MockBIBO mock) {
        return mock.skipping();
    }

    @Override
    public byte[] transceive(byte[] bytes) throws BIBOException {
        if (closed) {
            throw new BIBOException("MockBIBO: closed");
        }
        if (pairs.isEmpty()) {
            throw new BIBOException("MockBIBO: depleted");
        }
        if (skipping) {
            while (!pairs.isEmpty()) {
                var pair = pairs.removeFirst();
                if (pair.response == null) {
                    throw new BIBOException("MockBIBO: configured to throw");
                }
                if (Arrays.equals(bytes, pair.command)) {
                    return pair.response;
                }
            }
            throw new BIBOException("MockBIBO: no matching command for %s".formatted(HexUtils.bin2hex(bytes)));
        }
        var pair = pairs.removeFirst();
        if (pair.response == null) {
            throw new BIBOException("MockBIBO: configured to throw");
        }
        if (pair.command != null && !Arrays.equals(bytes, pair.command)) {
            throw new BIBOException("MockBIBO: expected %s but got %s".formatted(HexUtils.bin2hex(pair.command), HexUtils.bin2hex(bytes)));
        }
        return pair.response;
    }

    @Override
    public void close() {
        closed = true;
    }
}
