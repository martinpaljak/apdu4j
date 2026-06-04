// SPDX-FileCopyrightText: 2025 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.remote;

import apdu4j.core.BIBO;
import apdu4j.core.BIBOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HexFormat;
import java.util.function.Function;

// Reverse of the server
public class JCSDKClient implements Function<String, BIBO>, BIBO {
    private static final Logger log = LoggerFactory.getLogger(JCSDKClient.class);

    final String host;
    final int port;
    private SocketChannel channel;
    private byte[] atr;

    private boolean closed;

    public JCSDKClient(String host, int port) {
        this.port = port;
        this.host = host;
    }

    static RemoteMessage recv(SocketChannel channel) throws IOException {
        log.trace("Trying to read header ...");
        ByteBuffer hdr = ByteBuffer.allocate(4);
        int len = channel.read(hdr);
        if (len < 0) {
            log.info("Peer closed connection");
            throw new EOFException("Peer closed connection");
        }
        byte b1 = hdr.get(0);
        log.info("Received {}", HexFormat.of().formatHex(hdr.array()));
        switch (b1) {
            case (byte) 0xF0:
                int atrlen = hdr.getShort(2);
                ByteBuffer atr = ByteBuffer.allocate(atrlen);
                channel.read(atr);
                return new RemoteMessage(RemoteMessage.Type.ATR, atr.array());
            case 0x00:
                int cmdlen = hdr.getInt(0);
                ByteBuffer cmd = ByteBuffer.allocate(cmdlen);
                do {
                    channel.read(cmd);
                    log.trace("Read {} out of {}", cmd.position(), cmd.limit());
                } while (cmd.position() < cmd.limit());
                return new RemoteMessage(RemoteMessage.Type.APDU, cmd.array());
            default:
                throw new IOException("Unknown command header: %s" + HexFormat.of().formatHex(hdr.array()));
        }
    }

    static RemoteMessage send(SocketChannel channel, RemoteMessage message) throws IOException {
        log.info("Sending " + message.getType());
        switch (message.getType()) {
            case APDU:
                channel.write(JCSDKServer.format((byte) 0x00, message.getPayload()));
                break;
            case ATR:
                channel.write(JCSDKServer.format((byte) 0xF0, new byte[0]));
                break;
            case POWERDOWN:
                channel.write(JCSDKServer.format((byte) 0xFE, new byte[0]));
                channel.close();
                // Server also closes connection after it.
                return null;
            default:
                log.warn("Unknown message for protocol: " + message.getType());
        }
        RemoteMessage received = recv(channel);
        log.trace("Received {}: {}", received.getType(), HexFormat.of().formatHex(received.getPayload()));
        return received;
    }

    public SocketChannel getSocket() throws IOException {
        return AbstractTCPAdapter.connect(host, port);
    }

    public void reset() {
        // FIXME: Deprecated
        try {
            send(this.channel, new RemoteMessage(RemoteMessage.Type.POWERDOWN));
            this.atr = send(this.channel, new RemoteMessage(RemoteMessage.Type.ATR)).getPayload();
        } catch (IOException e) {
            throw new BIBOException(e.getMessage(), e);
        }
    }

    public byte[] getATR() {
        if (this.atr == null) {
            reset();
        }
        return this.atr.clone();
    }

    @Override
    public byte[] transceive(byte[] commandAPDU) {
        try {
            return send(this.channel, new RemoteMessage(RemoteMessage.Type.APDU, commandAPDU)).getPayload();
        } catch (IOException e) {
            throw new BIBOException(e.getMessage(), e);
        }
    }

    public String getProtocol() {
        return "T=1"; // FIXME
    }

    @Override
    public BIBO apply(String protocol) {
        try {
            JCSDKClient connection = new JCSDKClient(host, port);
            connection.channel = AbstractTCPAdapter.connect(host, port);
            connection.atr = send(connection.channel, new RemoteMessage(RemoteMessage.Type.ATR)).getPayload();
            return connection;
        } catch (IOException e) {
            throw new BIBOException(e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        log.trace("Closing connection");
        try {
            send(this.channel, new RemoteMessage(RemoteMessage.Type.POWERDOWN));
            closed = true;
            this.channel.close();
        } catch (IOException e) {
            log.error("Could not send POWERDOWN", e);
            throw new BIBOException(e.getMessage(), e);
        }
    }

    public boolean isClosed() {
        return closed;
    }
}
