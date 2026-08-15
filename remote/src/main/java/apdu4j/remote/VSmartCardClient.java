// SPDX-FileCopyrightText: 2025 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.remote;

import apdu4j.core.BIBO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.function.Function;

public final class VSmartCardClient extends AbstractTCPClient {
    private static final Logger log = LoggerFactory.getLogger(VSmartCardClient.class);

    // Default values
    public static final int DEFAULT_VSMARTCARD_PORT = 35963;
    public static final String DEFAULT_VSMARTCARD_HOST = "127.0.0.1";

    // Protocol:
    // We are a client, connecting to the vsmartcard driver
    // Server initiates messaging, to which we answer
    // messages are uint16, followed with payload
    // command messages are of length 1, where commands are:
    // 0x00 - power off (no response)
    // 0x01 - power on (no response)
    // 0x02 - reset (no response)
    // 0x04 - get ATR . replied with 0xXXYY length + atr
    // Everything else - command APDU, followed with response APDU.
    // See https://frankmorgner.github.io/vsmartcard/virtualsmartcard/api.html#creating-a-virtual-smart-card
    public VSmartCardClient(Function<String, BIBO> sim) {
        super(sim);
        port = DEFAULT_VSMARTCARD_PORT;
        host = DEFAULT_VSMARTCARD_HOST;
    }

    static ByteBuffer _send(byte[] data) throws IOException {
        if (data.length > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Too big payload");
        }
        ByteBuffer payload = ByteBuffer.allocate(2 + data.length);
        payload.putShort((short) data.length);
        payload.put(data);
        payload.rewind();
        return payload;
    }

    // The presence poll: an ATR means the card is here, an empty answer means it is gone. That
    // encoding is this format's own, so the poll is a message of this adapter's own.
    @Override
    protected RemoteMessage vendor(byte[] request) throws IOException {
        if (request.length != 1 || request[0] != 0x04) {
            throw new IOException("Unknown command: " + HexFormat.of().formatHex(request));
        }
        boolean gone = tapped() || !present();
        return new RemoteMessage(RemoteMessage.Type.VENDOR, gone ? new byte[0] : atr());
    }

    @Override
    protected void send(SocketChannel channel, RemoteMessage message) throws IOException {
        switch (message.type()) {
            case APDU, VENDOR -> channel.write(_send(message.payload()));
            // A power on and a power off are told, not asked, so nothing goes back for them.
            case POWERUP, POWERDOWN -> log.trace("Nothing to answer to {}", message.type());
            // This format has no way to say no, and a peer waiting for a response would wait
            // forever, so the connection goes instead: a card that cannot answer has left.
            case ERROR -> throw new IOException(new String(message.payload(), StandardCharsets.UTF_8));
        }
    }

    @Override
    protected RemoteMessage recv(SocketChannel channel) throws IOException {
        short len = read(channel, 2).getShort(0);
        if (len < 0) {
            throw new IOException("Received unexpected length: " + len);
        }

        // command
        if (len == 0x01) {
            byte cmd = read(channel, 1).get(0);
            return switch (cmd) {
                case 0x00 -> new RemoteMessage(RemoteMessage.Type.POWERDOWN);
                // A reset gets a fresh session, the same as a power on. On Windows and macOS a
                // connection starts with one.
                case 0x01, 0x02 -> new RemoteMessage(RemoteMessage.Type.POWERUP);
                default -> new RemoteMessage(RemoteMessage.Type.VENDOR, new byte[]{cmd});
            };
        }
        // APDU otherwise
        return new RemoteMessage(RemoteMessage.Type.APDU, read(channel, len).array());
    }
}
