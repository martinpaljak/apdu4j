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
package apdu4j;

import java.util.concurrent.CompletableFuture;

public interface AsynchronousBIBO {
    /**
     * Transceives a bunch of bytes to a secure element, asynchronously.
     * <p>
     * Comparable to:
     * <p>
     * IsoDep.transceive() in Android
     * SCardTransmit() in PC/SC
     * CardChannel.transmit(ByteBuffer, ByteBuffer) in javax.smartcardio
     * Channel.transmit() in OpenMobileAPI
     *
     * @param bytes payload
     * @return the bytes returned from the SE. The size should always be &gt;= 2 bytes
     * @throws BIBOException when transceive fails
     */
    CompletableFuture<byte[]> transmit(byte[] bytes) throws BIBOException;
}
