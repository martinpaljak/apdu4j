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

import static apdu4j.core.HexBytes.concatenate;

// Chains GET RESPONSE commands on SW1=0x61 (more data available)
public final class GetResponseWrapper implements BIBO {
    private final BIBO wrapped;

    public static GetResponseWrapper wrap(BIBO bibo) {
        return new GetResponseWrapper(bibo);
    }

    public GetResponseWrapper(BIBO bibo) {
        this.wrapped = bibo;
    }

    @Override
    public byte[] transceive(byte[] command) throws BIBOException {
        return chainOnSw1(wrapped, command, 0x61);
    }

    @Override
    public void close() {
        wrapped.close();
    }

    // Shared implementation for GET RESPONSE (0x61) and GET MORE DATA (0x9F) chaining
    static final int MAX_CHAIN_ROUNDS = 256;

    static byte[] chainOnSw1(BIBO wrapped, byte[] command, int triggerSw1) throws BIBOException {
        byte[] combined = new byte[0];
        var cmd = command;
        var cla = Byte.toUnsignedInt(command[0]);
        for (int i = 0; i < MAX_CHAIN_ROUNDS; i++) {
            try {
                var response = new ResponseAPDU(wrapped.transceive(cmd));
                if (response.getSW1() == triggerSw1) {
                    combined = concatenate(combined, response.getData());
                    cmd = new CommandAPDU(cla, 0xC0, 0x00, 0x00, response.getSW2()).getBytes();
                } else {
                    return concatenate(combined, response.getData(), response.getSWBytes());
                }
            } catch (IllegalArgumentException e) {
                throw new BIBOException("Invalid response APDU", e);
            }
        }
        throw new BIBOException("GET RESPONSE chaining exceeded %d rounds".formatted(MAX_CHAIN_ROUNDS));
    }
}
