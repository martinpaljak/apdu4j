// SPDX-FileCopyrightText: 2025 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.remote;

import apdu4j.core.BIBO;
import apdu4j.core.BIBOSA;
import apdu4j.core.CardInfo;
import apdu4j.core.HexBytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import apdu4j.remote.RemoteMessage.Type;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.function.Function;

// Minimal generalization to support multiple adapters and both servers and clients
public abstract class AbstractTCPAdapter implements Callable<Boolean> {

    private static final Logger log = LoggerFactory.getLogger(AbstractTCPAdapter.class);

    // TS 3B, TD1 offers T=0, TD2 offers T=1, TCK.
    public static final String DEFAULT_ATR_HEX = "3B80800101";
    // TS 3B, TD1 offers T=1 alone, TCK, so that a reader negotiating "*" settles on T=1.
    public static final String DEFAULT_T1_ATR_HEX = "3B800181";
    static final byte[] DEFAULT_ATR = HexFormat.of().parseHex(DEFAULT_ATR_HEX);
    static final byte[] DEFAULT_T1_ATR = HexFormat.of().parseHex(DEFAULT_T1_ATR_HEX);
    // What a card with nothing of its own to say is known by in the field.
    static final byte[] DEFAULT_UID = HexFormat.of().parseHex("04010203");
    private static final byte[] GET_UID = HexFormat.of().parseHex("FFCA000000");
    private static final byte[] SW_OK = HexFormat.of().parseHex("9000");

    // Setup before any peer is served. Nothing to do for a client.
    protected void start() throws IOException {
    }

    protected abstract RemoteMessage recv(SocketChannel channel) throws IOException;

    protected abstract void send(SocketChannel channel, RemoteMessage message) throws IOException;

    protected final Function<String, BIBO> sim;
    protected String configuredProtocol = "*"; // CLI-set protocol, "*" means dynamic
    protected String protocol = "*"; // runtime protocol, may be set by incoming messages
    protected String host;
    protected volatile int port;

    private volatile Thread thread; // Used to interrupt the adapter on shutdown
    private volatile BIBO session; // The card in use, null when closed
    private volatile boolean connected = true; // Is a card in the field
    private volatile boolean tap; // A tap the loop has not acted on yet
    private volatile boolean removed; // A tap not yet reported to the peer
    private volatile boolean running = true; // Cleared by shutdown() alone

    protected AbstractTCPAdapter(Function<String, BIBO> sim) {
        this.sim = sim;
    }

    public AbstractTCPAdapter withPort(int port) {
        this.port = port;
        return this;
    }

    public AbstractTCPAdapter withProtocol(String protocol) {
        this.configuredProtocol = protocol;
        this.protocol = protocol;
        return this;
    }

    public AbstractTCPAdapter withHost(String host) {
        this.host = host;
        return this;
    }

    // The card's answer to reset, as published by the open session. With no session, or one that
    // says nothing, a default the protocol in use can be negotiated from. A caller wanting a
    // specific ATR publishes it on the sessions it hands out.
    protected final byte[] atr() {
        byte[] fallback = "T=1".equals(protocol) ? DEFAULT_T1_ATR : DEFAULT_ATR;
        return session instanceof BIBOSA sa ? sa.preferences().valueOf(CardInfo.ATR).map(HexBytes::v).orElse(fallback) : fallback;
    }

    // The same, for the fact a contactless card is known by instead.
    protected final byte[] uid() {
        return session instanceof BIBOSA sa ? sa.preferences().valueOf(CardInfo.UID).map(HexBytes::v).orElse(DEFAULT_UID) : DEFAULT_UID;
    }

    // Every session opened by the adapter ends here, as soon as the reason to end it shows up.
    private void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    // The operations every adapter is served by, whatever it calls them on the wire.

    // Opens a session, closing an abandoned one, as vsmartcard on Linux powers up twice.
    protected final RemoteMessage powerup() {
        if (!connected) {
            return error("no card");
        }
        if (session != null) {
            log.warn("Powering up without powering down, closing previous session");
            closeSession();
        }
        try {
            session = sim.apply(protocol);
        } catch (RuntimeException e) {
            log.warn("Could not open a session: {}", e.getMessage(), e);
            return error(e.getMessage());
        }
        return new RemoteMessage(Type.POWERUP, atr());
    }

    protected final RemoteMessage powerdown() {
        closeSession();
        protocol = configuredProtocol; // reset for next session
        return new RemoteMessage(Type.POWERDOWN);
    }

    protected final RemoteMessage apdu(byte[] cmd) {
        if (session == null) {
            return error("no session");
        }
        if (Arrays.equals(cmd, GET_UID) && "T=CL".equals(protocol)) {
            // A question the card never sees: over a real reader the driver answers it from the field.
            log.info("Intercepting GET UID");
            return new RemoteMessage(Type.APDU, HexBytes.concatenate(uid(), SW_OK));
        }
        log.info(">> {}", HexFormat.of().formatHex(cmd));
        byte[] response = session.transceive(cmd);
        log.info("<< {}", HexFormat.of().formatHex(response));
        return new RemoteMessage(Type.APDU, response);
    }

    // Is a card in the field.
    protected final boolean present() {
        return connected;
    }

    // Is a session running on it.
    protected final boolean open() {
        return session != null;
    }

    // A tap shows to a peer that polls for presence as one absent answer. Reading it clears it.
    protected final boolean tapped() {
        boolean pending = removed;
        removed = false;
        return pending;
    }

    protected static RemoteMessage error(String reason) {
        return new RemoteMessage(Type.ERROR, reason.getBytes(StandardCharsets.UTF_8));
    }

    // A message of the adapter's own, answered by the adapter that knows what it means. The
    // returned message is sent to the peer, so reply instead of throwing: an exception here
    // drops the connection. Handed back unanswered by default, for a format that renders its
    // own messages in send().
    protected RemoteMessage vendor(byte[] request) throws IOException {
        return new RemoteMessage(Type.VENDOR, request);
    }

    // A card change asked for from another thread takes effect here, between messages.
    private void settle() {
        if (tap) {
            tap = false;
            removed = true; // a tap the peer never sees is not a tap
        } else if (connected) {
            return;
        }
        powerdown();
    }

    // The changes below are picked up by the adapter thread between messages, so safe to call
    // from any thread. A card that is not there arrives, a card that is there goes out and back.
    public void tap() {
        if (!connected) {
            log.info("Presenting card");
            connected = true;
        } else {
            log.info("Triggering tap");
            tap = true;
        }
    }

    public void connected(boolean flag) {
        log.info(flag ? "Card in the field" : "Taking card out");
        connected = flag;
    }

    public void shutdown() {
        log.trace("Shutting down adapter");
        running = false;
        // The one change that may take a socket with it, as nothing is served after it.
        thread.interrupt();
    }

    // One established peer: receive one request, apply one operation, send the format's answer.
    // A format that ends a peer by closing the channel is noticed here, not asked to say so.
    protected final void serve(SocketChannel channel) throws IOException {
        log.info("Serving peer {}", channel.getRemoteAddress());
        while (channel.isOpen() && !Thread.currentThread().isInterrupted() && running) {
            RemoteMessage msg;
            try {
                msg = recv(channel);
            } catch (EOFException e) {
                log.info("Peer disconnected");
                disconnected();
                return;
            }
            settle(); // a change asked for while waiting shows in this answer
            log.trace("Processing {}", msg.type());
            switch (msg.type()) {
                case POWERUP -> send(channel, powerup());
                case POWERDOWN -> send(channel, powerdown());
                case APDU -> send(channel, apdu(msg.payload()));
                case VENDOR -> send(channel, vendor(msg.payload()));
                default -> log.warn("Unhandled message type: {}", msg.type());
            }
        }
    }

    // The peer connection ended. It says nothing about the card, so what happens to the session
    // is the format's call: this one keeps it for the next peer.
    protected void disconnected() {
    }

    // One peer, from wherever the role gets them, served until it goes away. Failures a role can
    // recover from stay inside it, the rest end the run.
    protected abstract void peer() throws IOException;

    // Returns true if closed normally, false on errors
    @Override
    public final Boolean call() {
        this.thread = Thread.currentThread();
        this.thread.setName(this.getClass().getSimpleName());
        try {
            start();
            while (!Thread.currentThread().isInterrupted() && running) {
                peer();
            }
            log.info("Adapter thread done");
            return true;
        } catch (ClosedChannelException e) {
            log.trace("Interrupted while serving", e);
            return true;
        } catch (Exception e) {
            log.error("Adapter stopped: {}", e.getMessage(), e);
            return false;
        } finally {
            closeSession();
        }
    }

    // Exactly this many bytes, however many pieces TCP hands them over in.
    static ByteBuffer read(SocketChannel channel, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) {
            if (channel.read(buf) < 0) {
                throw new EOFException("Peer closed connection");
            }
        }
        return buf.flip();
    }

    // Connect to remote server
    public static SocketChannel connect(String host, Integer port) throws IOException {
        InetSocketAddress addr = new InetSocketAddress(host, port);
        SocketChannel sc = SocketChannel.open();
        sc.setOption(StandardSocketOptions.TCP_NODELAY, true);
        sc.socket().connect(addr, 3000); // TODO: tunable
        if (!sc.isConnected()) {
            throw new IOException("Could not connect to " + addr);
        }
        return sc;
    }

    @Override
    public String toString() {
        return String.format("%s{host=%s port=%d atr=%s protocol=%s}", this.getClass().getSimpleName(), host, port, HexFormat.of().formatHex(atr()), protocol);
    }
}
