/*
 * Copyright (c) 2019 Martin Paljak
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package apdu4j.pcsc;

import apdu4j.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

/**
 * This "flattens" a javax.smartcardio.Card with logical channels API into a simple BIBO stream.
 */
public class CardBIBO implements BIBO, AsynchronousBIBO {
    private static final Logger logger = LoggerFactory.getLogger(CardBIBO.class);
    public static final String PROP_APDU4J_PSEUDOAPDU = "apdu4j.pseudoapdu";
    public static final String ENV_APDU4J_PSEUDOAPDU = "APDU4J_PSEUDOAPDU";

    public static final String PROP_APDU4J_PCSC_RESET = "apdu4j.pcsc.reset";
    public static final String ENV_APDU4J_PCSC_RESET = "APDU4J_PCSC_RESET";

    protected final Card card;
    private volatile boolean closed = false;

    // set to false to disable pseudoapdu-s
    public boolean pseudo = Boolean.getBoolean(System.getProperty(PROP_APDU4J_PSEUDOAPDU, System.getenv().getOrDefault(ENV_APDU4J_PSEUDOAPDU, Boolean.TRUE.toString())));
    public boolean reset = Boolean.getBoolean(System.getProperty(PROP_APDU4J_PCSC_RESET, System.getenv().getOrDefault(ENV_APDU4J_PCSC_RESET, Boolean.TRUE.toString())));

    protected HashMap<Integer, CardChannel> channels = new HashMap<>();

    protected CardBIBO(Card card) {
        this.card = card;
        channels.put(0, card.getBasicChannel());
    }

    protected int getChannel(int cla) {
        // TODO: validate this logic here.
        if ((cla & 0x80) == 0x80)
            return 0;
        if ((cla & 0xE0) == 0x00) {
            return cla & 0x03;
        } else if ((cla & 0x40) == 0x40) {
            return (cla & 0x0F) + 4;
        } else
            return 0;
    }

    public static CardBIBO wrap(Card card) {
        return new CardBIBO(card);
    }

    @Override
    public byte[] transceive(byte[] bytes) throws BIBOException {
        if (closed)
            throw new BIBOException("has been closed!");
        try {
            int channel = getChannel(bytes[0] & 0xFF);

            // Intercept OPEN CHANNEL
            if (bytes.length <= 5 && ((bytes[0] & 0x80) == 0x00) && bytes[1] == 0x70 && bytes[2] == 0x00 && bytes[3] == 0x00) {
                CardChannel l = card.openLogicalChannel();
                channels.put(l.getChannelNumber(), l);
                return new byte[]{(byte) l.getChannelNumber(), (byte) 0x90, 0x00};
            }

            // intercept CLOSE CHANNEL
            if (bytes.length == 4 && bytes[1] == 0x70 && bytes[2] == (byte) 0x80 && bytes[3] == 0x00) {
                channels.get(channel).close();
                return new byte[]{(byte) 0x90, 0x00};
            }

            if (pseudo) {
                // Pseudo APDU - get ATR
                if (Arrays.equals(bytes, HexUtils.hex2bin("FFCA100000"))) {
                    byte[] atr = card.getATR().getBytes();
                    atr = Arrays.copyOf(atr, atr.length + 2);
                    atr[atr.length - 2] = (byte) 0x90;
                    return atr;
                }
                // Pseudo APDU - get protocol
                if (Arrays.equals(bytes, HexUtils.hex2bin("FFCA110000"))) {
                    switch (card.getProtocol().toUpperCase()) {
                        case "T=0":
                            return HexUtils.hex2bin("009000");
                        case "T=1":
                            return HexUtils.hex2bin("019000");
                        case "DIRECT":
                            return HexUtils.hex2bin("109000");
                        default:
                            return HexUtils.hex2bin("FF9000");
                    }
                }
                // Pseudo APDU - get UID
                if (Arrays.equals(Arrays.copyOf(bytes, 5), HexUtils.hex2bin("FFCA000000"))) {
                    // T=0 can't be contactless, thus no UID
                    if (card.getProtocol().equals("T=0"))
                        return new byte[]{0x6A, (byte) 0x81};
                    // Passthrough, handled by reader
                }
            }
            if (!channels.containsKey(channel))
                throw new BIBOException("Channel not open: " + channel);
            // Some readers/drivers return zero length response
            // See https://github.com/martinpaljak/GlobalPlatformPro/issues/307
            byte[] resp = channels.get(channel).transmit(new CommandAPDU(bytes)).getBytes();
            if (resp.length < 2)
                throw new BIBOException("Broken incoming data: " + HexUtils.bin2hex(resp));
            return resp;
        } catch (CardException e) {
            String r = SCard.getExceptionMessage(e);
            // TODO: try to localize SCARD_E_NOT_TRANSACTED to possibly contactless readers
            if (r.equals(SCard.SCARD_E_NOT_TRANSACTED) || r.equals(SCard.SCARD_E_NO_SMARTCARD)) {
                logger.debug("Assuming tag removed, because {}", r);
                throw new TagRemovedException(r, e);
            }
            throw new BIBOException(e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            card.disconnect(reset);
        } catch (CardException e) {
            String err = SCard.getExceptionMessage(e);
            if (err.equals(SCard.SCARD_E_INVALID_HANDLE)) {
                logger.debug("Ignoring {} during disconnect, already closed before", err);
            } else
                logger.warn("disconnect() failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<byte[]> transmit(byte[] command) {
        if (closed)
            return CompletableFuture.failedFuture(new BIBOException("closed"));
        // FIXME: do not execute this in common pool
        return CompletableFuture.supplyAsync(() -> transceive(command));
    }
}
