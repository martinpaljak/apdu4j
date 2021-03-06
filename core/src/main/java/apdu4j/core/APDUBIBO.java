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

// Extension on top of BIBO, not unline CardChannel, which allows
// to transmit APDU-s back and forth over BIBO. Drop-in replacement for
// code that currently uses javax.smartcardio *APDU, with a new import for *APDU
public class APDUBIBO implements BIBO {
    final BIBO bibo;

    public APDUBIBO(BIBO bibo) {
        this.bibo = bibo;
    }

    public ResponseAPDU transmit(CommandAPDU command) throws BIBOException {
        return new ResponseAPDU(bibo.transceive(command.getBytes()));
    }

    @Override
    public byte[] transceive(byte[] bytes) throws BIBOException {
        return bibo.transceive(bytes);
    }

    public ResponseAPDU transceive(CommandAPDU command) throws BIBOException {
        return new ResponseAPDU(bibo.transceive(command.getBytes()));
    }

    @Override
    public void close() {
        bibo.close();
    }
}
