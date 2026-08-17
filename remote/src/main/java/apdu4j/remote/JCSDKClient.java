// SPDX-FileCopyrightText: 2025 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.remote;

import apdu4j.core.BIBO;
import apdu4j.core.BIBOException;
import apdu4j.core.BIBOSA;
import apdu4j.core.CardInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public JCSDKClient(String host, int port) {
        this.port = port;
        this.host = host;
    }

    static RemoteMessage recv(SocketChannel channel) throws IOException {
        JCSDKServer.Frame frame = JCSDKServer.parse(channel);
        return switch (frame.code()) {
            case (byte) 0xF0 -> new RemoteMessage(RemoteMessage.Type.POWERUP, frame.payload());
            case 0x00 -> new RemoteMessage(RemoteMessage.Type.APDU, frame.payload());
            default -> throw new IOException("Unknown command code: %02X".formatted(frame.code()));
        };
    }

    static RemoteMessage send(SocketChannel channel, RemoteMessage message) throws IOException {
        log.trace("Sending {}", message.type());
        switch (message.type()) {
            case APDU:
                channel.write(JCSDKServer.format((byte) 0x00, message.payload()));
                break;
            case POWERUP:
                channel.write(JCSDKServer.format((byte) 0xF0, new byte[0]));
                break;
            case POWERDOWN:
                channel.write(JCSDKServer.format((byte) 0xFE, new byte[0]));
                channel.close();
                // Server also closes connection after it.
                return null;
            default:
                log.warn("Unknown message for protocol: {}", message.type());
        }
        RemoteMessage received = recv(channel);
        if (log.isTraceEnabled()) {
            log.trace("Received {}: {}", received.type(), HexFormat.of().formatHex(received.payload()));
        }
        return received;
    }

    @Override
    public byte[] transceive(byte[] commandAPDU) {
        try {
            return send(this.channel, new RemoteMessage(RemoteMessage.Type.APDU, commandAPDU)).payload();
        } catch (IOException e) {
            throw new BIBOException(e.getMessage(), e);
        }
    }

    // Powering up reports the ATR of the card behind the connection, published on the session so
    // that whoever serves it announces the card by its own answer to reset.
    @Override
    public BIBO apply(String protocol) {
        try {
            var connection = new JCSDKClient(host, port);
            connection.channel = AbstractTCPAdapter.connect(host, port);
            byte[] atr = send(connection.channel, new RemoteMessage(RemoteMessage.Type.POWERUP)).payload();
            return new BIBOSA(connection, CardInfo.params(atr, protocol));
        } catch (IOException e) {
            throw new BIBOException(e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        log.trace("Closing connection");
        try {
            send(this.channel, new RemoteMessage(RemoteMessage.Type.POWERDOWN));
            this.channel.close();
        } catch (IOException e) {
            log.warn("Could not send POWERDOWN: {}", e.getMessage(), e);
            throw new BIBOException(e.getMessage(), e);
        }
    }
}
