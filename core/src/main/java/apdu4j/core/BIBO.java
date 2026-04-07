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

import java.util.function.Function;

/**
 * Bytes In, Bytes Out - the fundamental smart card transport abstraction.
 *
 * <p>A BIBO sends a bag of bytes and receives a bag of bytes (or an exception).
 * Command-response. Half-duplex. Synchronous. This is equal to {@code SCardTransmit}
 * in PC/SC, {@code CardChannel.transmit()} in {@code javax.smartcardio},
 * {@code IsoDep.transceive()} on Android, etc.
 *
 * <p>The technical requirements for implementing a BIBO are intentionally left
 * undefined - could be a UNIX pipe, a UDP message, JSON over HTTP, PC/SC API,
 * serial I2C, Bluetooth LE, or anything that satisfies the simple interface contract.
 *
 * <p>BIBO is <b>not</b> thread-safe by design. Implementations assume single-threaded
 * access. For multi-threaded use, wrap with a serializing proxy (e.g.
 * {@code ReaderExecutor.wrap()}) that pins all operations to a dedicated per-reader thread.
 *
 * <p>BIBO is a functional interface: {@link #transceive} is the only abstract method,
 * so any {@code byte[] -> byte[]} lambda can be used where a BIBO is expected.
 * {@link #close} defaults to a no-op for resource-less transports.
 *
 * @see APDUBIBO
 * @see MockBIBO
 */
@FunctionalInterface
public interface BIBO extends AutoCloseable {
    /**
     * Sends a command and returns the response, synchronously.
     *
     * @param bytes command APDU bytes (4 to 65544 bytes for ISO 7816-4)
     * @return the bytes returned from the card, always &gt;= 2 bytes (at minimum SW1 SW2)
     * @throws BIBOException when transceive fails or BIBO has been closed
     */
    byte[] transceive(byte[] bytes) throws BIBOException;

    /**
     * Wraps this BIBO with a decorator, enabling fluent pipeline construction:
     * <pre>{@code
     * var bibo = transport
     *     .then(GetResponseWrapper::wrap)
     *     .then(b -> LoggingBIBO.wrap(b, System.out));
     * }</pre>
     *
     * @param wrapper function that wraps this BIBO and returns the decorated BIBO
     * @return the wrapped BIBO
     */
    default BIBO then(Function<BIBO, BIBO> wrapper) {
        return wrapper.apply(this);
    }

    /**
     * Releases resources held by this BIBO. After {@code close()}, calling
     * {@link #transceive} throws {@link IllegalStateException}. Idempotent.
     *
     * <p>Defaults to a no-op so resource-less BIBOs (lambdas, in-memory mocks)
     * need not implement it.
     */
    @Override
    default void close() {
        // No-op by default; transport-backed implementations override.
    }
}
