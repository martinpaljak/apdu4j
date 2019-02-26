import apdu4j.HexUtils;
import apdu4j.TerminalManager;
import jnasmartcardio.Smartcardio;
import org.testng.SkipException;
import org.testng.annotations.Test;

import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class TestReaderSelection {
    @Test
    public void testReaderSelectionATR() {
        try {
            List<CardTerminal> t = TerminalManager.byATR(Collections.singleton(HexUtils.hex2bin("3BFA1800008031FE45FE654944202F20504B4903")));
            if (t.size() > 0) {
                System.out.println(String.join("\n", t.stream().map(CardTerminal::getName).collect(Collectors.toList())));
            }
        } catch (CardException | NoSuchAlgorithmException | Smartcardio.EstablishContextException e) {
            throw new SkipException("No readers found");
        }
    }

    @Test
    public void testReaderSelectionAID() {
        try {
            List<CardTerminal> t = TerminalManager.byAID(Collections.singleton(HexUtils.hex2bin("D23300000045737445494420763335")));
            if (t.size() > 0) {
                System.out.println(String.join("\n", t.stream().map(CardTerminal::getName).collect(Collectors.toList())));
            }
        } catch (CardException | NoSuchAlgorithmException | Smartcardio.EstablishContextException e) {
            throw new SkipException("No readers found");
        }
    }
}
