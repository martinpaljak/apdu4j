/*
 * Copyright (c) 2020-present Martin Paljak
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

import java.util.concurrent.ExecutionException;

/**
 * Wraps the provided AsynchronousBIBO with blocking calls to get a BIBO
 */
public class BlockingBIBO implements BIBO {
    private final AsynchronousBIBO wrapped;

    public BlockingBIBO(AsynchronousBIBO bibo) {
        wrapped = bibo;
    }

    @Override
    public byte[] transceive(byte[] bytes) throws BIBOException {
        try {
            return wrapped.transmit(bytes).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof BIBOException)
                throw (BIBOException) e.getCause();
            else throw new BIBOException("Failed to get response: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            throw new BIBOException("Failed waiting for response: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        wrapped.close();
    }
}
