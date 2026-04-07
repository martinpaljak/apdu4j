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

// Strips the trailing Le from short Case 4 APDUs (CLA INS P1 P2 Lc Data Le ->
// CLA INS P1 P2 Lc Data) so they go on the wire as Case 3. Intended for T=0
// sessions where the host wants to drive GET RESPONSE chaining itself instead
// of relying on the IFD's TPDU translation. Pair with GetResponseWrapper to
// consume the resulting 61 XX procedure bytes:
//
//   stack.compose(GetResponseWrapper::wrap, T0Stripper::wrap)
//
// Pass-through (unchanged):
//   - Case 1 (header only)
//   - Case 2 short / Case 2 extended (Le-only)
//   - Case 3 short / Case 3 extended (Lc + data, no Le)
//   - Any extended-length APDU (T=0 cannot carry extended APDUs anyway)
public final class T0Stripper implements BIBO {
    private final BIBO wrapped;

    public static T0Stripper wrap(BIBO bibo) {
        return new T0Stripper(bibo);
    }

    private T0Stripper(BIBO bibo) {
        this.wrapped = bibo;
    }

    @Override
    public byte[] transceive(byte[] command) throws BIBOException {
        return wrapped.transceive(strip(command));
    }

    // Package-private for unit tests. Returns the same array (no copy) when no stripping needed.
    static byte[] strip(byte[] apdu) {
        // Need at least header(4) + Lc(1) + Data(>=1) + Le(1) = 7 to be a Case 4 short
        if (apdu.length < 7) {
            return apdu;
        }
        int l1 = Byte.toUnsignedInt(apdu[4]);
        // Lc=0 marks an extended-length APDU; T=0 is short-only, leave it alone
        if (l1 == 0) {
            return apdu;
        }
        // Case 4 short: total length = header(4) + Lc(1) + Lc bytes + Le(1)
        if (apdu.length == 4 + 1 + l1 + 1) {
            var stripped = new byte[apdu.length - 1];
            System.arraycopy(apdu, 0, stripped, 0, stripped.length);
            return stripped;
        }
        // Case 3 short or some other shape we don't touch
        return apdu;
    }

    @Override
    public void close() {
        wrapped.close();
    }
}
