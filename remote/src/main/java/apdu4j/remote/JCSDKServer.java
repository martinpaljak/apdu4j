// SPDX-FileCopyrightText: 2025 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.remote;

import apdu4j.core.BIBO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.function.Function;

public final class JCSDKServer extends AbstractTCPServer {
    // Protocol: clients (like PC/SC adapter or javax.smartcardio library)
    // connect to us.
    // Protocol: uint32 followed with payload.
    // Command messages in high byte: 0xFE power down, 0xF0 send ATR
    // 0x00 messages encode length of APDU-s
    private static final Logger log = LoggerFactory.getLogger(JCSDKServer.class);

    public static final int DEFAULT_JCSDK_PORT = 9025;
    public static final String DEFAULT_JCSDK_HOST = "0.0.0.0";

    // A frame is a length with the command code in the byte the length never reaches.
    record Frame(byte code, byte[] payload) {
    }

    static ByteBuffer format(byte code, byte[] data) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + data.length);
        buffer.putInt(data.length);
        buffer.put(0, code);
        buffer.position(4);
        buffer.put(data);
        buffer.rewind();
        if (log.isTraceEnabled()) {
            log.trace("Wrote {}", HexFormat.of().formatHex(buffer.array()));
        }
        return buffer;
    }

    // Both ends of this format read the wire the same way, and disagree only on what a code means.
    static Frame parse(SocketChannel channel) throws IOException {
        ByteBuffer header = read(channel, 4);
        if (log.isTraceEnabled()) {
            log.trace("Read header {}", HexFormat.of().formatHex(header.array()));
        }
        return new Frame(header.get(0), read(channel, header.getInt(0) & 0xFFFFFF).array());
    }

    public JCSDKServer(Function<String, BIBO> sim) {
        super(sim);
        host = DEFAULT_JCSDK_HOST;
        port = DEFAULT_JCSDK_PORT;
    }

    // The connection is the session, so a peer that hangs up takes the card down with it.
    @Override
    protected void disconnected() {
        powerdown();
    }

    @Override
    protected RemoteMessage recv(SocketChannel channel) throws IOException {
        Frame frame = parse(channel);
        return switch (frame.code()) {
            // The first thing a client sends, asking for the ATR of the card behind the
            // connection. It opens the session it reports.
            case (byte) 0xF0 -> new RemoteMessage(RemoteMessage.Type.POWERUP);
            case (byte) 0xFE -> new RemoteMessage(RemoteMessage.Type.POWERDOWN);
            case 0x00 -> new RemoteMessage(RemoteMessage.Type.APDU, frame.payload());
            default -> throw new IOException("Unknown command code: %02X".formatted(frame.code()));
        };
    }

    @Override
    protected void send(SocketChannel channel, RemoteMessage message) throws IOException {
        log.trace("Sending {}", message.type());
        switch (message.type()) {
            case APDU -> channel.write(format((byte) 0x00, message.payload()));
            case POWERUP -> channel.write(format((byte) 0xF0, message.payload()));
            // The connection is the session, so ending one ends the other. A peer that asked for
            // a card and got a closed socket reads it as no card, which is what happened.
            case POWERDOWN -> channel.close();
            case ERROR -> {
                log.info("Closing peer connection: {}", new String(message.payload(), StandardCharsets.UTF_8));
                channel.close();
            }
            default -> log.warn("Unknown message for protocol: {}", message.type());
        }
    }
}
