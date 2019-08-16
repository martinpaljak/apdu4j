package apdu4j;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import java.io.IOException;

public class CardBIBO implements BIBO {
    protected final Card card;
    protected final CardChannel channel;

    protected CardBIBO(Card card) {
        this.card = card;
        this.channel = card.getBasicChannel();
    }

    public static CardBIBO wrap(Card card) {
        return new CardBIBO(card);
    }

    @Override
    public byte[] transceive(byte[] bytes) throws IOException {
        try {
            return channel.transmit(new CommandAPDU(bytes)).getBytes();
        } catch (CardException e) {
            String r = TerminalManager.getExceptionMessage(e);
            if (r.equals("SCARD_E_NOT_TRANSACTED") || r.equals("SCARD_E_NO_SMARTCARD")) {
                throw new TagRemovedException(r, e);
            }
            throw new IOException("Transmit failed: " + e.getMessage(), e);
        }
    }
}
