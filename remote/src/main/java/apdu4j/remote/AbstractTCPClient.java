// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.remote;

import apdu4j.core.BIBO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.function.Function;

// The adapter goes to the peer: one server dialed, and dialed again for as long as the run lasts.
public abstract class AbstractTCPClient extends AbstractTCPAdapter {
    private static final Logger log = LoggerFactory.getLogger(AbstractTCPClient.class);

    // A peer that is not up yet is normal here, so dialing is retried at a human pace.
    static final long RETRY_MS = 1000;

    protected AbstractTCPClient(Function<String, BIBO> sim) {
        super(sim);
    }

    @Override
    protected final void peer() throws IOException {
        try (SocketChannel channel = connect(host, port)) {
            serve(channel);
        } catch (ClosedChannelException e) {
            throw e;
        } catch (IOException e) {
            log.info("Peer {}:{} is gone, dialing again: {}", host, port, e.getMessage());
            log.trace("Exception", e);
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
