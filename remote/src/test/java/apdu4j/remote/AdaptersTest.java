// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.remote;

import apdu4j.core.BIBO;
import apdu4j.core.BIBOException;
import apdu4j.core.BIBOSA;
import apdu4j.core.CardInfo;
import apdu4j.core.CommandAPDU;
import apdu4j.core.HexBytes;
import apdu4j.core.MockBIBO;
import apdu4j.core.ResponseAPDU;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class AdaptersTest {
    private static final String ATR = AbstractTCPAdapter.DEFAULT_ATR_HEX.toLowerCase();
    private static final String CARD_ATR = "3b959440ffae0101000b";
    private static final String CARD_UID = "0a0b0c0d";
    private static final String AID = "a000000151000000";
    private static final String MISSING_AID = "a0000000030000";

    // A JSON protocol of open/apdu/close plus commands of its own, in the shape an
    // out-of-tree adapter would take.
    static final class CardAdapter extends AbstractJSONAdapter {
        CardAdapter(Function<String, BIBO> sim) {
            super(sim);
        }

        @Override
        protected RemoteMessage request(JsonNode json) {
            return switch (json.path("command").asText()) {
                case "open" -> {
                    if ("*".equals(configuredProtocol)) {
                        protocol = json.path("protocol").asText("*");
                    }
                    yield new RemoteMessage(RemoteMessage.Type.POWERUP);
                }
                case "apdu" -> new RemoteMessage(RemoteMessage.Type.APDU, HexFormat.of().parseHex(json.path("data").asText()));
                case "close" -> new RemoteMessage(RemoteMessage.Type.POWERDOWN);
                default -> new RemoteMessage(RemoteMessage.Type.VENDOR);
            };
        }

        @Override
        protected JsonNode response(JsonNode request, RemoteMessage message) {
            return switch (message.type()) {
                // A powerup carries the ATR of the session it opened.
                case POWERUP, APDU -> reply(request).put("response", HexFormat.of().formatHex(message.payload()));
                case POWERDOWN -> reply(request);
                case ERROR -> reply(request).put("error", new String(message.payload(), StandardCharsets.UTF_8));
                case VENDOR -> vendor(request);
            };
        }

        // Commands of this adapter's own, deciding for themselves what to do with the session.
        private JsonNode vendor(JsonNode request) {
            String command = request.path("command").asText();
            return switch (command) {
                case "notify" -> null; // nothing goes back
                case "select" -> select(request);
                case "turboselect" -> turboselect(request.path("aid").asText());
                default -> reply(request).put("error", "unsupported: " + command);
            };
        }

        // Runs on the open session, or on one of its own when nothing is open.
        private JsonNode select(JsonNode request) {
            byte[] aid = HexFormat.of().parseHex(request.path("aid").asText());
            RemoteMessage selected = apdu(aid);
            if (selected.type() == RemoteMessage.Type.APDU) {
                return reply(request).put("selected", HexFormat.of().formatHex(selected.payload()));
            }
            try (BIBO temporary = sim.apply(protocol)) {
                return reply(request).put("selected", HexFormat.of().formatHex(temporary.transceive(aid)));
            }
        }

        // Powers the card up if it has to, and leaves it as it was found. The answer says what
        // was asked and what came of it, in a shape of its own.
        private JsonNode turboselect(String aid) {
            boolean borrowed = !open();
            boolean selected = false;
            if (!borrowed || powerup().type() == RemoteMessage.Type.POWERUP) {
                RemoteMessage response = apdu(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, HexFormat.of().parseHex(aid)).getBytes());
                selected = response.type() == RemoteMessage.Type.APDU && new ResponseAPDU(response.payload()).getSW() == 0x9000;
                if (borrowed) {
                    powerdown();
                }
            }
            ObjectNode answer = mapper.createObjectNode();
            answer.putObject("turboselect").put("aid", aid).put("selected", selected);
            return answer;
        }
    }

    private final List<MockBIBO> cards = new ArrayList<>();
    private AbstractTCPAdapter adapter;
    private ExecutorService executor;

    // Every session gets the same card, publishing an ATR and a UID of its own. It answers the
    // commands below in the order they come, skipping the ones a session has no use for.
    private Function<String, BIBO> sim() {
        cards.clear();
        return protocol -> {
            MockBIBO card = MockBIBO.with(select(AID), "9000")
                    .then(select(MISSING_AID), "6a82")
                    .then("00a40400", "6f009000")
                    .then("80ca9f7f", "9f7f109000")
                    .then("00b0000000", "01029000")
                    .skipping();
            cards.add(card);
            return new BIBOSA(card, CardInfo.params(HexFormat.of().parseHex(CARD_ATR), "T=1").with(CardInfo.UID, HexBytes.v(CARD_UID)));
        };
    }

    // SELECT by name, as the adapter builds it.
    private static String select(String aid) {
        return HexFormat.of().formatHex(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, HexFormat.of().parseHex(aid)).getBytes());
    }

    // Serves the adapter on a port of the system's choosing, returning it once bound.
    private int serve(AbstractTCPAdapter adapter) throws Exception {
        this.adapter = adapter;
        adapter.withHost("127.0.0.1");
        adapter.withPort(0);
        executor = Executors.newSingleThreadExecutor();
        executor.submit(adapter);
        for (int i = 0; i < 500 && adapter.port == 0; i++) {
            Thread.sleep(10);
        }
        return adapter.port;
    }

    @AfterMethod
    public void stop() throws Exception {
        adapter.shutdown();
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    // A card session through its life: a connection streaming requests, then a connection per
    // request, then a vendor command with no card open.
    @Test
    public void testCardSession() throws Exception {
        CardAdapter json = new CardAdapter(sim());
        int port = serve(json);

        try (Client client = new Client(port)) {
            // The ATR of the card the session opened, not the adapter's default.
            assertEquals(client.ask("{\"command\":\"open\"}").path("response").asText(), CARD_ATR);

            // Powering up twice, as vsmartcard does on Linux, closes the abandoned session.
            assertEquals(client.ask("{\"command\":\"open\"}").path("response").asText(), CARD_ATR);
            assertEquals(cards.size(), 2);
            expectThrows(BIBOException.class, () -> cards.get(0).transceive(new byte[]{0}));

            assertEquals(client.ask("{\"command\":\"apdu\",\"data\":\"00a40400\"}").path("response").asText(), "6f009000");
            // Vendor command, running on the session opened above.
            assertEquals(client.ask("{\"command\":\"select\",\"aid\":\"80ca9f7f\"}").path("selected").asText(), "9f7f109000");
        }

        try (Client client = new Client(port)) {
            assertEquals(client.ask("{\"command\":\"apdu\",\"data\":\"00b0000000\"}").path("response").asText(), "01029000", "the session survives the connection");
            assertTrue(client.ask("{\"command\":\"close\"}").path("error").isMissingNode());
        }
        assertEquals(cards.size(), 2);
        expectThrows(BIBOException.class, () -> cards.get(1).transceive(new byte[]{0}));

        try (Client client = new Client(port)) {
            assertEquals(client.ask("{\"command\":\"select\",\"aid\":\"00a40400\"}").path("selected").asText(), "6f009000");
        }
        assertEquals(cards.size(), 3, "a session of the vendor command's own");
        expectThrows(BIBOException.class, () -> cards.get(2).transceive(new byte[]{0}));

        // A format with no presence poll learns of a card the only way it can: nothing opens.
        try (Client client = new Client(port)) {
            adapter.connected(false);
            assertEquals(client.ask("{\"command\":\"open\"}").path("error").asText(), "no card", "the card is out");

            // A vendor command of a shape of its own, answering what it was asked rather than
            // echoing the question. With no card there is nothing to power up.
            JsonNode answer = client.ask(turboselect(AID));
            assertTrue(answer.path("command").isMissingNode(), "the answer is not the question");
            assertEquals(answer.path("turboselect").path("aid").asText(), AID);
            assertFalse(answer.path("turboselect").path("selected").asBoolean(), "no card to select on");
            assertEquals(cards.size(), 3, "a card that is not there opens nothing");

            // A card that is there is powered up for it, and left as it was found.
            adapter.tap();
            assertTrue(client.ask(turboselect(AID)).path("turboselect").path("selected").asBoolean());
            assertEquals(cards.size(), 4, "a session of its own");
            expectThrows(BIBOException.class, () -> cards.get(3).transceive(new byte[]{0}));
            assertEquals(client.ask("{\"command\":\"apdu\",\"data\":\"00a40400\"}").path("error").asText(), "no session", "and closed again");

            // A session it did not open is a session it does not close.
            assertEquals(client.ask("{\"command\":\"open\"}").path("response").asText(), CARD_ATR);
            assertFalse(client.ask(turboselect(MISSING_AID)).path("turboselect").path("selected").asBoolean(), "the card says no");
            assertEquals(cards.size(), 5, "the open session served it");
            assertEquals(client.ask("{\"command\":\"apdu\",\"data\":\"00a40400\"}").path("response").asText(), "6f009000", "still open");

            adapter.connected(false);
            assertEquals(client.ask("{\"command\":\"apdu\",\"data\":\"00a40400\"}").path("error").asText(), "no session", "the session left with the card");
        }
    }

    private static String turboselect(String aid) {
        return "{\"command\":\"turboselect\",\"aid\":\"" + aid + "\"}";
    }

    // The JCSDK client and server as a pair, the card reached over its own wire format.
    @Test
    public void testJCSDKPair() throws Exception {
        JCSDKServer server = new JCSDKServer(sim());
        int port = serve(server);

        JCSDKClient reader = new JCSDKClient("127.0.0.1", port);
        try (var card = (BIBOSA) reader.apply("*")) {
            // Asking for the ATR is this format's powerup, so the session publishes the card's own.
            assertEquals(card.preferences().valueOf(CardInfo.ATR).orElseThrow(), HexBytes.v(CARD_ATR));
            assertEquals(HexFormat.of().formatHex(card.transceive(HexFormat.of().parseHex("00a40400"))), "6f009000");
            assertEquals(HexFormat.of().formatHex(card.transceive(HexFormat.of().parseHex("80ca9f7f"))), "9f7f109000");
        }
        assertEquals(cards.size(), 1);
        expectThrows(BIBOException.class, () -> cards.get(0).transceive(new byte[]{0}));

        // With no card there is no ATR to report and no way to say so, so the peer is left with
        // a closed connection, which is what a card that is not there looks like.
        server.connected(false);
        expectThrows(BIBOException.class, () -> reader.apply("*"));
    }

    // The vsmartcard wire format, with this test standing in for the driver the adapter dials
    // into: one byte commands, a length prefix each way, presence read from the ATR poll alone.
    @Test
    public void testVSmartCardProtocol() throws Exception {
        try (ServerSocketChannel driver = ServerSocketChannel.open()) {
            driver.bind(new InetSocketAddress("127.0.0.1", 0));
            adapter = new VSmartCardClient(sim()).withHost("127.0.0.1").withPort(driver.socket().getLocalPort())
                    .withProtocol("T=CL");
            executor = Executors.newSingleThreadExecutor();
            executor.submit(adapter);

            try (SocketChannel peer = driver.accept()) {
                // No session yet, so the card is announced with a default ATR.
                assertEquals(ask(peer, (byte) 0x04), ATR);

                tell(peer, (byte) 0x01); // power on, answered by saying nothing
                assertEquals(ask(peer, (byte) 0x04), CARD_ATR, "the ATR of the card the session opened");
                assertEquals(ask(peer, HexFormat.of().parseHex("00a40400")), "6f009000");
                // A question the card never sees, answered from what the session published.
                assertEquals(ask(peer, HexFormat.of().parseHex("FFCA000000")), CARD_UID + "9000");

                tell(peer, (byte) 0x00); // power off, also answered by saying nothing
                assertEquals(ask(peer, (byte) 0x04), ATR, "the card is still here, its session is not");
                expectThrows(BIBOException.class, () -> cards.get(0).transceive(new byte[]{0}));

                adapter.tap();
                assertEquals(ask(peer, (byte) 0x04), "", "a tap the peer never sees is not a tap");
                assertEquals(ask(peer, (byte) 0x04), ATR, "and the card is back, with no session");

                adapter.connected(false);
                assertEquals(ask(peer, (byte) 0x04), "", "the card is out");
                adapter.connected(true);
                assertEquals(ask(peer, (byte) 0x04), ATR);

                // An APDU with no session is an error this format cannot carry. A peer waiting
                // for a response is never left waiting, so the connection goes instead.
                tell(peer, HexFormat.of().parseHex("00a40400"));
                expectThrows(EOFException.class, () -> AbstractTCPAdapter.read(peer, 2));
            }

            // The card comes back, because a client dials again after the peer is gone.
            try (SocketChannel peer = driver.accept()) {
                assertEquals(ask(peer, (byte) 0x04), ATR);

                // A command this format does not have costs that connection too.
                tell(peer, (byte) 0x03);
                expectThrows(EOFException.class, () -> AbstractTCPAdapter.read(peer, 2));
            }
        }
    }

    // The driver frames a command with its length, and the adapter answers the same way.
    private static void tell(SocketChannel peer, byte... command) throws IOException {
        peer.write(ByteBuffer.allocate(2 + command.length).putShort((short) command.length).put(command).flip());
    }

    private static String ask(SocketChannel peer, byte... command) throws IOException {
        tell(peer, command);
        int length = AbstractTCPAdapter.read(peer, 2).getShort(0);
        return HexFormat.of().formatHex(AbstractTCPAdapter.read(peer, length).array());
    }

    // Messages the adapter does not serve are answered, never dropped. Neither is a card that
    // refuses to open, which is the same event to the peer as a card that is not there.
    @Test
    public void testUnknownMessages() throws Exception {
        CardAdapter json = new CardAdapter(protocol -> {
            throw new BIBOException("card is busy");
        });
        int port = serve(json);

        try (Client client = new Client(port)) {
            assertEquals(client.ask("{\"command\":\"nosuchthing\"}").path("error").asText(), "unsupported: nosuchthing");
            // Requests that are not objects have no command either.
            assertEquals(client.ask("[]").path("error").asText(), "unsupported: ");

            // A command answering nothing sends nothing, leaving the connection ready.
            client.tell("{\"command\":\"notify\"}");
            assertEquals(client.ask("{\"command\":\"open\"}").path("error").asText(), "card is busy");
        }
    }

    // Line oriented client: every reply is one compact JSON value on its own line.
    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader in;

        Client(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setTcpNoDelay(true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        void tell(String request) throws IOException {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        }

        JsonNode ask(String request) throws IOException {
            tell(request);
            String line = in.readLine();
            assertFalse(line == null, "no reply to " + request);
            return AbstractJSONAdapter.mapper.readTree(line);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
