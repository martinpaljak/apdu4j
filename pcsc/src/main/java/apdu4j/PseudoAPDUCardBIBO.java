package apdu4j;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import java.io.IOException;
import java.util.Arrays;

public class PseudoAPDUCardBIBO extends CardBIBO {

    protected PseudoAPDUCardBIBO(Card card) {
        super(card);
    }

    public static PseudoAPDUCardBIBO wrap(Card card) {
        return new PseudoAPDUCardBIBO(card);
    }

    @Override
    public byte[] transceive(byte[] bytes) throws IOException {
        // Pseudo APDU - get ATR FIXME: specify and track existing implementations
        if (Arrays.equals(bytes, HexUtils.hex2bin("FF01000000"))) {
            byte[] atr = card.getATR().getBytes();
            atr = Arrays.copyOf(atr, atr.length + 2);
            atr[atr.length - 2] = (byte) 0x90;
            return atr;
        }
        // Pseudo APDU - get protocol
        if (Arrays.equals(bytes, HexUtils.hex2bin("FF02000000"))) {
            switch (card.getProtocol()) {
                case "T=0":
                    return HexUtils.hex2bin("009000");
                case "T=1":
                    return HexUtils.hex2bin("019000");
                default:
                    return HexUtils.hex2bin("FF9000");
            }
        }
        return super.transceive(bytes);
    }
}
