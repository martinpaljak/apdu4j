// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

import java.io.PrintStream;
import java.util.function.Consumer;

public final class LoggingBIBO implements BIBO {
    private final BIBO bibo;
    private final Consumer<String> sink;
    private final String prefix;

    private LoggingBIBO(BIBO bibo, Consumer<String> sink, String prefix) {
        this.bibo = bibo;
        this.sink = sink;
        this.prefix = prefix;
    }

    public static BIBO wrap(BIBO bibo, Consumer<String> sink) {
        return new LoggingBIBO(bibo, sink, "");
    }

    public static BIBO wrap(BIBO bibo, Consumer<String> sink, String prefix) {
        return new LoggingBIBO(bibo, sink, prefix);
    }

    public static BIBO wrap(BIBO bibo, PrintStream out) {
        return new LoggingBIBO(bibo, out::println, "");
    }

    public static BIBO wrap(BIBO bibo, PrintStream out, String prefix) {
        return new LoggingBIBO(bibo, out::println, prefix);
    }

    @Override
    public byte[] transceive(byte[] bytes) throws BIBOException {
        String cmdLog;
        try {
            cmdLog = new CommandAPDU(bytes).toLogString();
        } catch (IllegalArgumentException e) {
            // Malformed APDU is logged via sink with [malformed] tag, not as exception
            cmdLog = HexUtils.bin2hex(bytes) + " [malformed]";
        }
        sink.accept("%s>> %s".formatted(prefix, cmdLog));
        var start = System.nanoTime();
        try {
            var response = bibo.transceive(bytes);
            sink.accept("%s<< %s (%s)".formatted(prefix, new ResponseAPDU(response).toLogString(), nanoTime(System.nanoTime() - start)));
            return response;
        } catch (BIBOException e) {
            sink.accept("%s<< [error] %s (%s)".formatted(prefix, e.getMessage(), nanoTime(System.nanoTime() - start)));
            throw e;
        }
    }

    public static String nanoTime(long nanos) {
        long ms = nanos / 1_000_000;
        if (ms > 1000) {
            return "%ds%dms".formatted(ms / 1000, ms % 1000);
        }
        if (ms < 3) {
            long us = nanos / 10_000;
            return "%d.%02dms".formatted(us / 100, us % 100);
        }
        return ms + "ms";
    }

    @Override
    public void close() {
        bibo.close();
    }
}
