/*
 * Copyright (c) 2020-present Martin Paljak
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
package apdu4j.pcsc.terminals;

import apdu4j.core.BIBOException;
import apdu4j.core.HexUtils;
import apdu4j.core.SmartCardAppFutures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.*;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

import static apdu4j.core.SmartCardAppListener.CardData.ATR_BYTES;

// Adapter for apps, exposing synchronous javax.smartcardio interface
public final class SynthesizedCardTerminal extends CardTerminal {
    private static final Logger logger = LoggerFactory.getLogger(SynthesizedCardTerminal.class);

    private SynthesizedCard card;
    private SynthesizedCard.SynthesizedChannel channel;

    private final SmartCardAppFutures reader; // event source

    public SynthesizedCardTerminal(SmartCardAppFutures app) {
        this.reader = app;
        card = new SynthesizedCard();
    }

    @Override
    public String getName() {
        logger.trace("getName()");
        return "CloudSmartCard emulated reader";
    }


    @Override
    public Card connect(String s) throws CardException {
        logger.trace("connect(" + s + ")");

        if (!reader.getCardPresentFuture().isDone())
            throw new CardNotPresentException("Card not present!");

        try {
            Map props = reader.getCardPresentFuture().join();
            card._atr = Optional.ofNullable((byte[]) props.get(ATR_BYTES)).orElse(HexUtils.hex2bin("3b00"));
            logger.trace("connect(" + s + ") == {}", card);
            return card;
        } catch (CompletionException e) {
            throw new CardException("Could not: " + e.getMessage(), e.getCause());
        }
    }

    @Override
    public boolean isCardPresent() throws CardNotPresentException {
        boolean r = reader.getCardPresentFuture().isDone();
        logger.debug("card future: {}", reader.getCardPresentFuture());
        return r;
    }

    @Override
    public boolean waitForCardPresent(long l) throws CardException {
        logger.debug("waitForCardPresent({})", l);
        // Wait for reader message, but not longer than asked for
        CompletableFuture cf = reader.getCardPresentFuture();
        try {
            if (l == 0) {
                cf.get(10, TimeUnit.MINUTES);
                return true;
            } else {
                cf.get(l, TimeUnit.MILLISECONDS);
                return true;
            }
        } catch (InterruptedException e) {
            logger.warn("waitForCardPresent was interrupted");
            Thread.currentThread().interrupt(); // FIXME: not by spec, but we do it anyway
            return false;
        } catch (ExecutionException e) {
            logger.debug("card future failed");
            throw new CardException(e.getCause().getMessage(), e); // FIXME: cause here
        } catch (TimeoutException e) {
            logger.debug("card future timed out");
            return false;
        }
    }

    @Override
    public boolean waitForCardAbsent(long l) throws CardException {
        // This is a do-nothing? or wait for error message?
        logger.trace("CardTerminal#waitForCardAbsent({})", l);
        return false;
    }

    class SynthesizedCard extends Card {
        byte[] _atr;

        SynthesizedCard() {
            channel = new SynthesizedChannel();
        }

        @Override
        public ATR getATR() {
            logger.trace("Card#getATR() == {}", HexUtils.bin2hex(_atr));
            return new ATR(_atr);
        }

        @Override
        public String getProtocol() {
            // TODO: if message contains protocol, don't lie
            String protocol = "T=1";
            logger.trace("Card#getProtocol() == {}", protocol);
            return protocol;
        }

        @Override
        public CardChannel getBasicChannel() {
            logger.trace("Card#getBasicChannel()");
            return channel;
        }

        @Override
        public CardChannel openLogicalChannel() {
            logger.trace("Card#openLogicalChannel()");
            // return a new bibo that does this TODO
            throw new IllegalStateException("Not implemented");
        }

        @Override
        public void beginExclusive() {
            logger.trace("Card#beginExclusive()");
            // Do nothing
        }

        @Override
        public void endExclusive() {
            logger.trace("Card#endExclusive()");
            // Do nothing
        }

        @Override
        public byte[] transmitControlCommand(int i, byte[] bytes) throws CardException {
            logger.error("Card#transmitControlCommand() is not implemented");
            throw new CardException("Cloud apps should not use Card#transmitControlCommand()");
        }

        @Override
        public void disconnect(boolean b) {
            logger.trace("Card#disconnect({})", b);
            reader.close(); //reader.disconnect(); FIXME - API here sucks
        }

        @Override
        public String toString() {
            return String.format("Card protocol: %s atr: %s", getProtocol(), HexUtils.bin2hex(getATR().getBytes()));
        }

        class SynthesizedChannel extends CardChannel {

            @Override
            public Card getCard() {
                logger.trace("CardChannel#getCard()");
                return SynthesizedCard.this;
            }

            @Override
            public int getChannelNumber() {
                // Only basic channel
                int result = 0;
                logger.trace("CardChannel#getChannelNumber() == {}", result);
                return result;
            }

            @Override
            public ResponseAPDU transmit(CommandAPDU commandAPDU) throws CardException {
                if (!isCardPresent())
                    throw new IllegalStateException("Card or channel has been closed");
                logger.trace("transmit({})", HexUtils.bin2hex(commandAPDU.getBytes()));
                try {
                    return new ResponseAPDU(reader.transmit(commandAPDU.getBytes()).get());
                } catch (BIBOException e) {
                    throw new CardException(e.getMessage(), e);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    throw new CardException(e.getMessage(), e);
                } catch (ExecutionException e) {
                    e.printStackTrace();
                    throw new CardException(e.getMessage(), e);
                }
            }

            @Override
            public int transmit(ByteBuffer byteBuffer, ByteBuffer byteBuffer1) throws CardException {
                logger.trace("transmit({})", HexUtils.bin2hex(byteBuffer.array()));
                throw new CardException("Cloud apps should not use Card#transmit()");
                // FIXME: implement it
            }

            @Override
            public void close() {
                logger.trace("CardChannel#close()");
                // FIXME: API. Send CLOSE CHANNEL
            }
        }
    }
}
