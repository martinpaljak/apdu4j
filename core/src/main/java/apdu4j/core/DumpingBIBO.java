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
        var start = System.nanoTime();
        var response = bibo.transceive(bytes);
        var elapsed = (System.nanoTime() - start) / 1_000_000;
        out.println(HexUtils.bin2hex(bytes));
        out.println("# %dms".formatted(elapsed));
        out.println(HexUtils.bin2hex(response));
        return response;
    }

    @Override
    public void close() {
        out.flush();
        bibo.close();
    }
}
