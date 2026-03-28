/*
 * Copyright (c) 2019-present Martin Paljak <martin@martinpaljak.net>
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

// Retries with correct Le when card responds with SW1=0x6C (wrong length)
public final class RetryWithRightLengthWrapper implements BIBO {
    private final BIBO wrapped;

    public static RetryWithRightLengthWrapper wrap(BIBO bibo) {
        return new RetryWithRightLengthWrapper(bibo);
    }

    public RetryWithRightLengthWrapper(BIBO bibo) {
        this.wrapped = bibo;
    }

    @Override
    public byte[] transceive(byte[] command) throws BIBOException {
        try {
            var response = new ResponseAPDU(wrapped.transceive(command));
            if (response.getSW1() == 0x6C) {
                var orig = new CommandAPDU(command);
                var data = orig.getNc() > 0 ? orig.getData() : null;
                var cmd = new CommandAPDU(orig.getCLA(), orig.getINS(), orig.getP1(), orig.getP2(), data, response.getSW2());
                return wrapped.transceive(cmd.getBytes());
            }
            return response.getBytes();
        } catch (IllegalArgumentException e) {
            throw new BIBOException("Invalid response APDU", e);
        }
    }

    @Override
    public void close() {
        wrapped.close();
    }
}
