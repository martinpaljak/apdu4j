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
            listener.onStart(new CompletableFuture<>());
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
