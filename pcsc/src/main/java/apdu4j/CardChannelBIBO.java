package apdu4j;

import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import java.io.IOException;

public class CardChannelBIBO implements BIBO {
    private final CardChannel channel;

    private CardChannelBIBO(CardChannel channel) {
        this.channel = channel;
    }

    public static APDUBIBO getBIBO(CardChannel channel) {
        return new APDUBIBO(new CardChannelBIBO(channel));
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
