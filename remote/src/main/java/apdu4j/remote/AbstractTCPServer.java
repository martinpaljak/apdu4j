// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.remote;

import apdu4j.core.BIBO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.function.Function;

// Peers come to the adapter: one socket bound for the whole run, one peer served at a time.
public abstract class AbstractTCPServer extends AbstractTCPAdapter {
    private static final Logger log = LoggerFactory.getLogger(AbstractTCPServer.class);

    private ServerSocketChannel server;

    protected AbstractTCPServer(Function<String, BIBO> sim) {
        super(sim);
    }

    @Override
    protected final void start() throws IOException {
        server = ServerSocketChannel.open().bind(new InetSocketAddress(host, port));
        port = server.socket().getLocalPort(); // the port is known once the system has chosen one
        log.info("Listening on {}", server.getLocalAddress());
    }

    @Override
    protected final void peer() throws IOException {
        try (SocketChannel channel = server.accept()) {
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            serve(channel);
        } catch (IOException e) {
            if (!server.isOpen()) {
                throw e; // the listening socket went, so there is nothing left to accept
            }
            // A peer that broke costs that peer. The next one is accepted as usual.
            log.info("Peer is gone, accepting the next: {}", e.getMessage());
            log.trace("Exception", e);
        }
    }
}
