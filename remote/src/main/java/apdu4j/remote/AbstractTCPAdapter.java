// SPDX-FileCopyrightText: 2025 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.remote;

import apdu4j.core.BIBO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import apdu4j.remote.RemoteMessage.Type;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

// Minimal generalization to support multiple adapters and both servers and clients
public abstract class AbstractTCPAdapter implements Callable<Boolean> {

    private final Logger log = LoggerFactory.getLogger(AbstractTCPAdapter.class);

    public static final String DEFAULT_ATR_HEX = "3B80800101";
    static final byte[] DEFAULT_ATR = HexFormat.of().parseHex(DEFAULT_ATR_HEX);

    protected void start() throws IOException {
        // No special steps needed for clients.
    }

    public enum AdapterState {
        CONNECTED, DISCONNECTED, RESET, SHUTDOWN
    }

    protected abstract RemoteMessage recv(SocketChannel channel) throws IOException;

    protected abstract void send(SocketChannel channel, RemoteMessage message) throws IOException;

    protected abstract SocketChannel getSocket() throws IOException;

    protected final Function<String, BIBO> sim;
    protected byte[] atr = DEFAULT_ATR;
    protected String configuredProtocol = "*"; // CLI-set protocol, "*" means dynamic
    protected String protocol = "*"; // runtime protocol, may be set by incoming messages
    protected String host;
    protected volatile int port;

    private volatile Thread thread; // Used to interrupt the adapter
    private volatile BIBO session;
    private AdapterState currentState = AdapterState.CONNECTED;

    private final AtomicReference<AdapterState> targetState = new AtomicReference<>(null);
    private final Semaphore semaphore = new Semaphore(1);

    protected AbstractTCPAdapter(Function<String, BIBO> sim) {
        this.sim = sim;
    }

    public AbstractTCPAdapter withATR(byte[] atr) {
        this.atr = atr.clone();
        return this;
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

    // Safe to call from any thread.
    public void tap() {
        log.info("Triggering tap");
        // indicate disconnect
        if (!targetState.compareAndSet(null, AdapterState.RESET)) {
            throw new IllegalStateException("Can't trigger reset!");
        }
        // interrupt thread
        thread.interrupt();
        // Wait until processed
        semaphore.acquireUninterruptibly();
    }

    public void connected(boolean flag) {
        if (!targetState.compareAndSet(null, flag ? AdapterState.CONNECTED : AdapterState.DISCONNECTED)) {
            throw new IllegalStateException("Can't toggle state!");
        }
        thread.interrupt();
        // Wait until processed
        semaphore.acquireUninterruptibly();
    }

    public void shutdown() {
        log.trace("Shutting down adapter...");
        targetState.set(AdapterState.SHUTDOWN);
        thread.interrupt();
    }

    // Returns true if closed normally, false on errors
    @Override
    public Boolean call() {
        this.thread = Thread.currentThread();
        this.thread.setName(this.getClass().getSimpleName());

        // Start listening or do other setup.
        try {
            start();
        } catch (Exception e) {
            log.error("Could not start: " + e.getMessage(), e);
            this.currentState = AdapterState.SHUTDOWN;
        }

        try {
            // Loop many clients / broken sessions
            while (!Thread.currentThread().isInterrupted()) {
                switch (currentState) {
                    case DISCONNECTED:
                        if (session != null) {
                            session.close();
                            session = null;
                            protocol = configuredProtocol;
                        }
                        try {
                            Thread.sleep(Long.MAX_VALUE);
                        } catch (InterruptedException e) {
                            log.debug("State changed, checking new state");
                            AdapterState target = targetState.getAndSet(null);
                            this.currentState = target == null ? AdapterState.SHUTDOWN : target;
                            semaphore.release();
                        }
                        continue;
                    case SHUTDOWN:
                        // Ctrl-C/Orderly shutdown of listening socket
                        log.info("Shutting down. Bye!");
                        return true;
                    case RESET:
                        if (session != null) {
                            session.close();
                            session = null;
                            protocol = configuredProtocol;
                        }
                        this.currentState = AdapterState.CONNECTED;
                        continue;
                    case CONNECTED:
                        try {
                            // New client.
                            SocketChannel channel = getSocket();
                            log.info("Serving peer {}", channel.getRemoteAddress());
                            // Many messages while connected
                            while (!Thread.currentThread().isInterrupted() && this.currentState == AdapterState.CONNECTED) {
                                try {
                                    RemoteMessage msg = recv(channel);
                                    // Silence noisy VSmartCard ATR request.
                                    if (!(this.getClass() == VSmartCardClient.class && msg.getType() == Type.ATR)) {
                                        log.trace("Processing {}", msg.getType());
                                    }
                                    switch (msg.getType()) {
                                        case ATR:
                                            // NOTE: this is spammed by vsmartcard on every second.
                                            // There's no way to indicate "there's no card, thus no ATR"
                                            send(channel, new RemoteMessage(Type.ATR, atr));
                                            break;
                                        case RESET:
                                            // NOTE: on Windows and macOS a connection "Starts" with a reset, so we open a connection on demand
                                            if (session != null) {
                                                session.close();
                                            }
                                            session = sim.apply(protocol);
                                            send(channel, new RemoteMessage(Type.RESET));
                                            break;
                                        case POWERUP:
                                            // Happens on Linux with vsmartcard
                                            if (session != null) {
                                                log.warn("Session is not null");
                                            }
                                            session = sim.apply(protocol);
                                            send(channel, new RemoteMessage(Type.POWERUP));
                                            break;
                                        case POWERDOWN:
                                            // Happens on mac/linux
                                            if (session != null) {
                                                session.close(); // FIXME: no reset ?
                                            }
                                            session = null;
                                            protocol = configuredProtocol; // reset for next session
                                            send(channel, new RemoteMessage(Type.POWERDOWN));
                                            break;
                                        case APDU:
                                            if (session == null) {
                                                log.warn("No session opened before APDU-s!");
                                                session = sim.apply(protocol);
                                            }
                                            byte[] cmd = msg.getPayload();
                                            if (Arrays.equals(cmd, HexFormat.of().parseHex("FFCA000000")) && "T=CL".equals(protocol)) {
                                                log.info("Intercepting GET UID");
                                                // NOTE: Normally it is the task of a reader driver to fetch the UID from the card
                                                // As we have basic virtual adapters, must intercept this ourselves.
                                                // TODO: parametrize
                                                send(channel, new RemoteMessage(Type.APDU, HexFormat.of().parseHex("040102039000")));
                                                break;
                                            }
                                            log.info(">> {}", HexFormat.of().formatHex(cmd));
                                            byte[] response = session.transceive(cmd);
                                            log.info("<< {}", HexFormat.of().formatHex(response));
                                            send(channel, new RemoteMessage(Type.APDU, response));
                                            break;
                                        default:
                                            log.warn("Unhandled message type: " + msg.getType());
                                    }
                                } catch (EOFException e) {
                                    log.info("Peer disconnected");
                                    break; // new socket or loop end
                                }
                            }
                        } catch (ClosedByInterruptException e) {
                            if (Thread.interrupted()) {
                                AdapterState target = targetState.getAndSet(null);
                                log.trace("interrupted with {}", target);
                                this.currentState = target == null ? AdapterState.SHUTDOWN : target;
                                semaphore.release(); // harmless on shutdown
                            }
                        } catch (SocketException | SocketTimeoutException e) {
                            log.error("Connection error: {}", e.getClass().getSimpleName());
                            log.trace("Exception", e);
                            return false;
                        } catch (IOException e) {
                            log.error("I/O error: {}", e.getClass().getSimpleName());
                            log.trace("Exception", e);
                        } catch (Exception e) {
                            log.error("Unhandled exception in adapter process", e);
                        }
                }
                log.trace("Adapter loop done");
            }
            log.info("Adapter thread done");
            return true;
        } finally {
            if (session != null) {
                session.close();
                session = null;
            }
        }
    }

    // Connect to remote server
    public static SocketChannel connect(String host, Integer port) throws IOException {
        InetSocketAddress addr = new InetSocketAddress(host, port);
        SocketChannel sc = SocketChannel.open();
        sc.socket().connect(addr, 3000); // TODO: tunable
        if (!sc.isConnected()) {
            throw new IOException("Could not connect to " + addr);
        }
        return sc;
    }

    // Start a local server
    public static ServerSocketChannel start(String host, Integer port) throws IOException {
        InetSocketAddress addr = new InetSocketAddress(host, port);
        ServerSocketChannel server = ServerSocketChannel.open();
        return server.bind(addr);
    }

    @Override
    public String toString() {
        return String.format("%s{host=%s port=%d atr=%s protocol=%s}", this.getClass().getSimpleName(), host, port, HexFormat.of().formatHex(atr), protocol);
    }
}
