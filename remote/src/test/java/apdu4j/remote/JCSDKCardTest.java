// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.remote;

import apdu4j.core.CommandAPDU;
import apdu4j.core.HexUtils;
import apdu4j.core.ResponseAPDU;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

// Reaches a card served by a JCSDK server elsewhere, named by JCSDK_SERVER as host or host:port.
// No such variable, no test.
public class JCSDKCardTest {

    // Selecting by name with no name asks for the card's default applet, the ISD on a
    // GlobalPlatform card.
    private static final String SELECT_ISD = "00A4040000";

    @Test
    public void testRemoteCard() {
        String server = System.getenv("JCSDK_SERVER");
        if (server == null) {
            throw new SkipException("Set JCSDK_SERVER=host[:port] to run against a JCSDK server");
        }
        String[] address = server.split(":");
        int port = address.length == 2 ? Integer.parseInt(address[1]) : JCSDKServer.DEFAULT_JCSDK_PORT;

        try (var card = (JCSDKClient) new JCSDKClient(address[0], port).apply("*")) {
            // TS is 3B or 3F, so anything else means this side read the wrong bytes. A pair of
            // our own would agree on a mistake, a foreign server does not.
            byte[] atr = card.getATR();
            assertTrue(atr.length > 2 && (atr[0] == 0x3B || atr[0] == 0x3F), "Not an ATR: " + HexUtils.bin2hex(atr));

            ResponseAPDU selected = card.transmit(new CommandAPDU(HexUtils.hex2bin(SELECT_ISD)));
            assertEquals(selected.getSW(), 0x9000, "SELECT failed: " + HexUtils.bin2hex(selected.getBytes()));
            assertEquals(selected.getData()[0], 0x6F, "Not a File Control Information template");
        }
    }
}
