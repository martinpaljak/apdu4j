// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

// BIBO decorator that threads state S through wrap/unwrap on each transceive cycle.
// Wrap transforms the outgoing command and evolves state; unwrap transforms the
// incoming response and evolves state again. The two together form one state step.
// Does not own the underlying BIBO - closing a session does not close the transport.
// If S implements AutoCloseable, close() calls it (e.g. for key zeroing).
public final class StatefulBIBO<S> implements BIBO {

    @FunctionalInterface
    public interface Wrap<S> {
        Stateful<CommandAPDU, S> apply(CommandAPDU command, S state);
    }

    @FunctionalInterface
    public interface Unwrap<S> {
        Stateful<ResponseAPDU, S> apply(ResponseAPDU response, S state);
    }

    private final BIBO bibo;
    private S state;
    private final Wrap<S> wrap;
    private final Unwrap<S> unwrap;

    public StatefulBIBO(BIBO bibo, S initialState, Wrap<S> wrap, Unwrap<S> unwrap) {
        this.bibo = bibo;
        this.state = initialState;
        this.wrap = wrap;
        this.unwrap = unwrap;
    }

    @Override
    public byte[] transceive(byte[] bytes) throws BIBOException {
        var command = new CommandAPDU(bytes);
        var wrapped = wrap.apply(command, state);
        // Thread wrap-evolved state into unwrap via local; commit only after full cycle.
        // If bibo.transceive() or unwrap throws, state retains its pre-cycle value.
        var response = new ResponseAPDU(bibo.transceive(wrapped.value().getBytes()));
        var unwrapped = unwrap.apply(response, wrapped.state());
        state = unwrapped.state();
        return unwrapped.value().getBytes();
    }

    public S state() {
        return state;
    }

    @Override
    public void close() {
        try {
            if (state instanceof AutoCloseable ac) {
                ac.close();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
