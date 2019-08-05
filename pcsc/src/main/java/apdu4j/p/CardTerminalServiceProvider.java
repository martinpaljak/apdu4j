package apdu4j.p;

import javax.smartcardio.CardTerminal;
import java.util.function.Function;
import java.util.function.Supplier;

public interface CardTerminalServiceProvider extends Supplier<Function<CardTerminal, Boolean>> {

}
