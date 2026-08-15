// SPDX-FileCopyrightText: 2025 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.remote;

import apdu4j.core.BIBO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.util.function.Function;

// JSON over TCP. Carries the connection and the framing, leaving the meaning of the messages
// to the adapter: request() says what a message is, response() says what goes back for it.
// A connection carries a stream of requests, or one request before the peer closes it.
public abstract class AbstractJSONAdapter extends AbstractTCPServer {
    private static final Logger log = LoggerFactory.getLogger(AbstractJSONAdapter.class);
    protected static final ObjectMapper mapper = new ObjectMapper();
    private static final ObjectWriter pretty = mapper.writerWithDefaultPrettyPrinter();

    public static final int DEFAULT_JSON_PORT = 9026;
    public static final String DEFAULT_JSON_HOST = "0.0.0.0";

    private SocketChannel peer;
    private MappingIterator<JsonNode> requests;
    private JsonNode current;

    protected AbstractJSONAdapter(Function<String, BIBO> sim) {
        super(sim);
        host = DEFAULT_JSON_HOST;
        port = DEFAULT_JSON_PORT;
    }

    // What the message means. Messages the adapter serves itself are Type.VENDOR.
    protected abstract RemoteMessage request(JsonNode json) throws IOException;

    // What goes back for it, or null to answer nothing. The request comes along, because a
    // format that answers in the shape of the question needs the question.
    protected abstract JsonNode response(JsonNode request, RemoteMessage message) throws IOException;

    // The request as a reply to add fields to.
    protected static ObjectNode reply(JsonNode request) {
        return request instanceof ObjectNode o ? o.deepCopy() : mapper.createObjectNode();
    }

    @Override
    protected final RemoteMessage recv(SocketChannel channel) throws IOException {
        if (channel != peer) {
            // New peer. The parser reads ahead, so it must live as long as the connection.
            peer = channel;
            requests = mapper.readerFor(JsonNode.class).readValues(Channels.newInputStream(channel));
        }
        if (!requests.hasNextValue()) {
            // The parser and the request it read belong to the connection that ended with them.
            peer = null;
            requests = null;
            current = null;
            throw new EOFException("Peer closed connection");
        }
        current = requests.nextValue();
        trace("Received", current);
        return request(current);
    }

    @Override
    protected final void send(SocketChannel channel, RemoteMessage message) throws IOException {
        JsonNode body = response(current, message);
        if (body == null) {
            log.trace("Nothing to answer to {}", message.type());
            return;
        }
        trace("Sending", body);
        // One compact value per line, so that both streaming and line reading clients work.
        byte[] json = mapper.writeValueAsBytes(body);
        ByteBuffer buf = ByteBuffer.allocate(json.length + 1);
        buf.put(json).put((byte) '\n').flip();
        channel.write(buf);
    }

    private void trace(String direction, JsonNode json) throws IOException {
        if (log.isTraceEnabled()) {
            log.trace("{}\n{}", direction, pretty.writeValueAsString(json));
        }
    }
}
