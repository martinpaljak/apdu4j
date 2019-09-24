/*
 * Copyright (c) 2019-2020 Martin Paljak
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
package apdu4j;

public class GetMoreDataWrapper implements BIBO {
    BIBO wrapped;

    public static GetMoreDataWrapper wrap(BIBO bibo) {
        return new GetMoreDataWrapper(bibo);
    }

    public GetMoreDataWrapper(BIBO bibo) {
        this.wrapped = bibo;
    }

    @Override
    public byte[] transceive(byte[] command) throws BIBOException {
        byte[] response = new byte[0];
        // No open loops
        for (int i = 0; i < 32; i++) {
            byte[] r = wrapped.transceive(command);
            ResponseAPDU res = new ResponseAPDU(r);
            response = concatenate(response, res.getData());
            if (res.getSW1() == 0x9F) {
                // XXX: dependence on CommandAPDU for 256
                command = new CommandAPDU(command[0], 0xC0, 0x00, 0x00, res.getSW2() == 0x00 ? 256 : res.getSW2()).getBytes();
            } else {
                response = concatenate(response, new byte[]{(byte) res.getSW1(), (byte) res.getSW2()});
                break;
            }
        }
        return response;
    }

    static byte[] concatenate(byte[]... args) {
        int length = 0, pos = 0;
        for (byte[] arg : args) {
            length += arg.length;
        }
        byte[] result = new byte[length];
        for (byte[] arg : args) {
            System.arraycopy(arg, 0, result, pos, arg.length);
            pos += arg.length;
        }
        return result;
    }

    @Override
    public void close() {
        wrapped.close();
    }
}
