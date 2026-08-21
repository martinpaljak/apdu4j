// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.remote;

import apdu4j.core.BIBO;
import apdu4j.core.BIBOException;
import apdu4j.core.BIBOSA;
import apdu4j.core.CardInfo;
import apdu4j.core.HexUtils;
import apdu4j.pcsc.PCSCReader;
import apdu4j.pcsc.Readers;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

// Presents a card to a vsmartcard installation on this host, then reaches it back through PC/SC,
// the apdu4j way. Needs the vsmartcard driver installed and pcscd running, which shows as a
// listed reader with no card in it. No such reader, no test.
public class VSmartCardTest {
    private static final String ATR = "3B959440FFAE0101000B";
    private static final String SELECT = "00A4040000";
    private static final String SELECTED = "6F0A8408A0000001510000009000";

    private AbstractTCPAdapter adapter;
    private ExecutorService executor;

    @AfterMethod
    public void stop() throws Exception {
        if (adapter != null) {
            adapter.shutdown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            adapter = null;
            Thread.sleep(1500); // let the reader notice the card left before the next one arrives
        }
    }

    // The virtual readers are listed whether or not anything serves them, so an empty one is a
    // driver waiting for a card. One with a card in it is somebody else's, and none at all means
    // vsmartcard is not installed here.
    private static PCSCReader waiting() {
        try {
            for (PCSCReader reader : Readers.select().list()) {
                if (reader.name().toLowerCase(Locale.ROOT).contains("vsmartcard") && !reader.present()) {
                    return reader;
                }
            }
        } catch (IllegalStateException | BIBOException e) {
            throw new SkipException("No PC/SC on this host: " + e.getMessage());
        }
        throw new SkipException("No vsmartcard reader waiting for a card, is vsmartcard installed?");
    }

    private static boolean present(String name) {
        return Readers.select().list().stream().anyMatch(r -> r.name().equals(name) && r.present());
    }

    // Serves the card and returns the name of the reader that picked it up.
    private String serve(AbstractTCPAdapter client) throws Exception {
        PCSCReader reader = waiting();
        adapter = client;
        executor = Executors.newSingleThreadExecutor();
        executor.submit(client);
        waitFor(() -> present(reader.name()), "The card never reached " + reader.name());
        return reader.name();
    }

    // A card that answers SELECT and refuses everything else. The host probes an appearing card
    // on its own (CryptoTokenKit on macOS), so this must answer anything, not a fixed script.
    private static BIBO card() {
        return command -> HexUtils.hex2bin(HexUtils.bin2hex(command).startsWith("00A40400") ? SELECTED : "6D00");
    }

    @Test
    public void testVirtualCard() throws Exception {
        String reader = serve(new VSmartCardClient(protocol -> new BIBOSA(card(), CardInfo.params(HexUtils.hex2bin(ATR), protocol))));

        Readers.select(reader).accept(card -> {
            assertEquals(card.preferences().valueOf(CardInfo.ATR).orElseThrow().s(), ATR, "the reader shows the ATR the session published");
            assertEquals(HexUtils.bin2hex(card.transceive(HexUtils.hex2bin(SELECT))), SELECTED);
        });

        // Taking the card out answers the presence poll empty, which the driver reads as a removal.
        adapter.connected(false);
        waitFor(() -> !present(reader), "card still present after removal");
        adapter.connected(true);
        waitFor(() -> present(reader), "card did not come back");
    }

    // A card that publishes no ATR of its own is presented with one the configured protocol
    // can be negotiated from.
    @Test
    public void testDefaultATR() throws Exception {
        String reader = serve(new VSmartCardClient(protocol -> card()).withProtocol("T=1"));

        Readers.select(reader).accept(card -> {
            assertEquals(card.preferences().valueOf(CardInfo.ATR).orElseThrow().s(), AbstractTCPAdapter.DEFAULT_T1_ATR_HEX);
            assertEquals(card.preferences().valueOf(CardInfo.NEGOTIATED_PROTOCOL).orElseThrow(), "T=1");
        });
    }

    private static void waitFor(Callable<Boolean> condition, String message) throws Exception {
        for (int i = 0; i < 50; i++) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(100);
        }
        fail(message);
    }
}
