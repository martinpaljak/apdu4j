package apdu4j.p;

import javax.smartcardio.CardTerminal;

public interface CardTerminalTouchApplicationProvider {
    TouchTerminalApplication get(CardTerminal reader, String protocol);
}
