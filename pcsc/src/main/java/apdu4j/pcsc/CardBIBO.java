// SPDX-FileCopyrightText: 2019 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import apdu4j.core.BIBO;
import apdu4j.core.BIBOException;
import apdu4j.core.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import java.util.HashMap;
import java.util.Map;

/**
 * This "flattens" a javax.smartcardio.Card with logical channels API into a simple BIBO stream.
 */
public final class CardBIBO implements BIBO {
    private static final Logger logger = LoggerFactory.getLogger(CardBIBO.class);
    private final Card card;
    private volatile boolean closed = false;

    private final SCard.Disconnect disconnect;

    private Map<Integer, CardChannel> channels = new HashMap<>();

    private CardBIBO(Card card, SCard.Disconnect disconnect) {
        this.card = card;
        this.disconnect = disconnect;
        channels.put(0, card.getBasicChannel());
    }

    int getChannel(int cla) {
        if ((cla & 0b1000_0000) != 0) { // 0x80
            return 0; // proprietary
        }
        if ((cla & 0b0100_0000) != 0) { // 0x40
            return (cla & 0b0000_1111) + 4; // 0x0F - further interindustry, channels 4-19
        }
        return cla & 0b0000_0011; // 0x03 - first interindustry, channels 0-3
    }

    public static CardBIBO wrap(Card card) {
        return new CardBIBO(card, SCard.Disconnect.RESET);
    }

    public static CardBIBO wrap(Card card, boolean reset) {
        return new CardBIBO(card, reset ? SCard.Disconnect.RESET : SCard.Disconnect.LEAVE);
    }

    public static CardBIBO wrap(Card card, SCard.Disconnect action) {
        return new CardBIBO(card, action);
    }

    @Override
    public byte[] transceive(byte[] bytes) throws BIBOException {
        if (closed) {
            throw new IllegalStateException("has been closed!");
        }
        try {
            var channel = getChannel(bytes[0] & 0xFF);

            // Intercept OPEN CHANNEL
            if (bytes.length <= 5 && ((bytes[0] & 0x80) == 0x00) && bytes[1] == 0x70 && bytes[2] == 0x00 && bytes[3] == 0x00) {
                // Call implementation, which issues a direct SCardTransmit for this
                var l = card.openLogicalChannel();
                channels.put(l.getChannelNumber(), l);
                return new byte[]{(byte) l.getChannelNumber(), (byte) 0x90, 0x00};
            }

            // intercept CLOSE CHANNEL: INS=70, P1=80, P2=channel number
            if (bytes.length == 4 && bytes[1] == 0x70 && bytes[2] == (byte) 0x80) {
                var channelToClose = bytes[3] & 0xFF;
                var toClose = channels.remove(channelToClose);
                if (toClose == null) {
                    throw new BIBOException("channel " + channelToClose + " not open");
                }
                toClose.close();
                return new byte[]{(byte) 0x90, 0x00};
            }

            // Require channel to be open
            if (!channels.containsKey(channel)) {
                throw new BIBOException("Channel not open: " + channel);
            }
            // Some readers/drivers return zero length response
            // See https://github.com/martinpaljak/GlobalPlatformPro/issues/307
            var resp = channels.get(channel).transmit(new CommandAPDU(bytes)).getBytes();
            if (resp.length < 2) {
                throw new BIBOException("Broken incoming data: " + HexUtils.bin2hex(resp));
            }
            return resp;
        } catch (CardException e) {
            String r = SCard.getExceptionMessage(e);
            if (SCard.SCARD_E_NOT_TRANSACTED.equals(r) || SCard.SCARD_E_NO_SMARTCARD.equals(r)) {
                logger.debug("Assuming tag removed, because {}", r);
                throw new BIBOException(r, e);
            }
            throw new BIBOException(e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            if (disconnect == SCard.Disconnect.UNPOWER && card instanceof jnasmartcardio.Smartcardio.JnaCard jnaCard) {
                jnaCard.disconnect(jnasmartcardio.Smartcardio.JnaCard.SCARD_UNPOWER_CARD);
            } else {
                card.disconnect(disconnect == SCard.Disconnect.RESET);
            }
        } catch (CardException e) {
            String err = SCard.getExceptionMessage(e);
            if (SCard.SCARD_E_INVALID_HANDLE.equals(err)) {
                logger.debug("Ignoring {} during disconnect, already closed before", err);
            } else {
                logger.warn("disconnect() failed: {}", e.getMessage(), e);
            }
        }
    }
}
