// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class DumpingBIBO implements BIBO {
    private final BIBO bibo;
    private final PrintStream out;

    private DumpingBIBO(BIBO bibo, PrintStream out) {
        this.bibo = bibo;
        this.out = out;
    }

    public static BIBO wrap(BIBO bibo, OutputStream out) {
        // Reuse existing PrintStream to preserve caller's flush/encoding settings
        var ps = out instanceof PrintStream p ? p : new PrintStream(out, true, StandardCharsets.UTF_8);
        return new DumpingBIBO(bibo, ps);
    }

    @Override
    public byte[] transceive(byte[] bytes) throws BIBOException {
        out.println(HexUtils.bin2hex(bytes));
        var start = System.nanoTime();
        try {
            var response = bibo.transceive(bytes);
            var elapsed = (System.nanoTime() - start) / 1_000_000;
            out.println("# %dms".formatted(elapsed));
            out.println(HexUtils.bin2hex(response));
            return response;
        } catch (BIBOException e) {
            var elapsed = (System.nanoTime() - start) / 1_000_000;
            out.println("# %dms %s".formatted(elapsed, e.getMessage()));
            throw e;
        }
    }

    @Override
    public void close() {
        out.flush();
        bibo.close();
    }
}
