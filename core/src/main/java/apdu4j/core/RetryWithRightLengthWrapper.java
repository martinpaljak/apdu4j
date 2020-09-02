/*
 * Copyright (c) 2019 Martin Paljak
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

public class RetryWithRightLengthWrapper implements BIBO {
    BIBO wrapped;

    public static RetryWithRightLengthWrapper wrap(BIBO bibo) {
        return new RetryWithRightLengthWrapper(bibo);
    }

    private RetryWithRightLengthWrapper(BIBO bibo) {
        this.wrapped = bibo;
    }

    @Override
    public byte[] transceive(byte[] command) throws BIBOException {
        byte[] r = wrapped.transceive(command);
        ResponseAPDU res = new ResponseAPDU(r);
        if (res.getSW1() == 0x6C) {
            r = wrapped.transceive(new CommandAPDU(command[0], command[1], command[2], command[3], res.getSW2()).getBytes());
        }
        return r;
    }

    @Override
    public void close() {
        wrapped.close();
    }
}
