// SPDX-FileCopyrightText: 2019 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
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
     * Typed sugar over {@link #transceive}: send a {@link CommandAPDU}, get a {@link ResponseAPDU}.
     * Equivalent to {@code javax.smartcardio.CardChannel#transmit(CommandAPDU)}.
     *
     * @param command the command APDU to send
     * @return the response APDU
     * @throws BIBOException when transceive fails or the response cannot be parsed as an APDU
     */
    default ResponseAPDU transmit(CommandAPDU command) throws BIBOException {
        try {
            return new ResponseAPDU(transceive(command.getBytes()));
        } catch (IllegalArgumentException e) {
            throw new BIBOException("Invalid response APDU", e);
        }
    }

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
