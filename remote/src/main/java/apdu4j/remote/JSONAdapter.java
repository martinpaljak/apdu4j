// SPDX-FileCopyrightText: 2025 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.remote;

import apdu4j.core.BIBO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.function.Function;

// JSON-over-TCP adapter. Each TCP connection carries one JSON request/response.
// Session state persists across connections via POWERUP/POWERDOWN lifecycle.
public final class JSONAdapter extends AbstractTCPAdapter {
    private static final Logger log = LoggerFactory.getLogger(JSONAdapter.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public static final int DEFAULT_JSON_PORT = 9026;
    public static final String DEFAULT_JSON_HOST = "0.0.0.0";

    ServerSocketChannel server;
    private JsonNode lastRequest;

    public JSONAdapter(Function<String, BIBO> sim) {
        super(sim);
        host = DEFAULT_JSON_HOST;
        port = DEFAULT_JSON_PORT;
    }

    @Override
    protected void start() throws IOException {
        server = start(host, port);
    }

    @Override
    protected SocketChannel getSocket() throws IOException {
        return server.accept();
    }

    @Override
    protected RemoteMessage recv(SocketChannel channel) throws IOException {
        // Read until EOF (one JSON object per connection, delimited by client closing output)
        var out = new ByteArrayOutputStream();
        var buf = ByteBuffer.allocate(4096);
        int read;
        while ((read = channel.read(buf)) >= 0) {
            buf.flip();
            out.write(buf.array(), 0, buf.limit());
            buf.clear();
        }
        if (out.size() == 0) {
            throw new EOFException("Peer closed connection");
        }

        JsonNode json = mapper.readTree(out.toByteArray());
        lastRequest = json;
        String command = json.path("command").asText();

        return switch (command) {
            case "open" -> {
                String proto = json.path("protocol").asText("*");
                if ("*".equals(configuredProtocol)) {
                    this.protocol = proto;
                }
                yield new RemoteMessage(RemoteMessage.Type.POWERUP);
            }
            case "apdu" -> {
                byte[] apdu = HexFormat.of().parseHex(json.path("data").asText());
                yield new RemoteMessage(RemoteMessage.Type.APDU, apdu);
            }
            case "close" -> new RemoteMessage(RemoteMessage.Type.POWERDOWN);
            default -> throw new IOException("Unknown command: " + command);
        };
    }

    @Override
    protected void send(SocketChannel channel, RemoteMessage message) throws IOException {
        ObjectNode json = lastRequest != null ? ((ObjectNode) lastRequest).deepCopy() : mapper.createObjectNode();
        switch (message.getType()) {
            case ATR:
                json.put("response", HexFormat.of().formatHex(message.getPayload()));
                break;
            case POWERUP:
                json.put("response", HexFormat.of().formatHex(atr));
                break;
            case APDU:
                json.put("response", HexFormat.of().formatHex(message.getPayload()));
                break;
            case POWERDOWN:
                break;
            case ERROR:
                if (message.getPayload() != null) {
                    json.put("error", new String(message.getPayload(), StandardCharsets.UTF_8));
                }
                break;
            default:
                log.warn("Unknown message for JSON protocol: " + message.getType());
                return;
        }
        lastRequest = null;
        byte[] out = mapper.writeValueAsBytes(json);
        channel.write(ByteBuffer.wrap(out));
    }
}
