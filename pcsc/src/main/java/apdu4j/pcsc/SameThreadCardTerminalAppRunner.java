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
package apdu4j.pcsc;

import apdu4j.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import java.util.concurrent.CompletableFuture;

// For testing. Run a single application (mostly) in the current thread
// This does not have a companion thread for card removal events and calls all callbacks from
// the thread calling run()
public class SameThreadCardTerminalAppRunner implements AsynchronousBIBO, Runnable {
    private static final Logger logger = LoggerFactory.getLogger(SameThreadCardTerminalAppRunner.class);

    final SmartCardAppListener listener;
    CardTerminal terminal;
    Card card;
    BIBO bibo;

    public SameThreadCardTerminalAppRunner(CardTerminal terminal, SmartCardAppListener listener) {
        this.terminal = terminal;
        this.listener = listener;
    }

    @Override
    public void run() {
        logger.info("Running");
        try {
            listener.onStart();
            card = terminal.connect("*");
            // Wait for card
            bibo = CardBIBO.wrap(card);
            byte[] ATR = card.getATR().getBytes();
            String protocol = card.getProtocol();
            SmartCardAppListener.CardData props = new SmartCardAppListener.CardData();
            props.put("atr", HexUtils.bin2hex(ATR));
            props.put("protocol", protocol);
            props.put("reader", terminal.getName());
            listener.onCardPresent(this, props);
        } catch (CardException e) {
            listener.onError(e);
        }
    }


    @Override
    public CompletableFuture<byte[]> transmit(byte[] apdu) {
        logger.info("SEND: " + HexUtils.bin2hex(apdu));
        try {
            byte[] response = bibo.transceive(apdu);
            logger.info("RECV: " + HexUtils.bin2hex(response));
            return CompletableFuture.completedFuture(response);
        } catch (BIBOException e) {
            listener.onError(e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public void close() {
        try {
            card.disconnect(true);
        } catch (CardException e) {
            e.printStackTrace();
        }
    }
}
