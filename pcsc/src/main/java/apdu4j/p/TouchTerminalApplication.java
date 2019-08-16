package apdu4j.p;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import java.util.Optional;
import java.util.function.Function;

public abstract class TouchTerminalApplication<T> implements Runnable {
    protected final CardTerminal reader;
    protected final String protocol;

    protected TouchTerminalApplication(CardTerminal reader, String protocol) {
        this.reader = reader;
        this.protocol = protocol;
    }

    protected Optional<T> touch(Function<Card, T> f) {
        try {
            while (true) {
                waitForCardAbsent(reader);
                waitForCardOrExit(reader);
                final Card card;
                try {
                    card = reader.connect(protocol);
                } catch (CardException e) {
                    System.err.println("W: Too fast, try again!");
                    e.printStackTrace();
                    Thread.sleep(300); // to avoid instant re-powering
                    continue;
                }
                // Run service
                T result = f.apply(card);
                card.disconnect(true);
                return Optional.of(result);

                /*int i = 0;
                boolean removed;
                // Wait until card is removed
                do {
                    removed = reader.waitForCardAbsent(1000);
                    if (i >= 10 && i % 10 == 0) {
                        System.err.println("W: Remove card!");
                    }
                } while (removed == false && i++ < 60);
                // Final check. If card has not been removed, fail
                if (!removed) {
                    System.err.println("E: Stuck card detected: " + reader.getName());
                }
                Thread.sleep(300); // to avoid re-powering
                return Optional.of(result);
                 */
            }
        } catch (CardException e) {
            e.printStackTrace();
            return Optional.empty();
        } catch (InterruptedException e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }

    private static void waitForCardOrExit(CardTerminal t) throws CardException {
        boolean found = false;
        System.err.print("Waiting for card ...");
        for (int i = 20; i > 0 && !found; i--) {
            found = t.waitForCardPresent(3000); // Wait for a minute in 3 second rounds
            System.err.print(".");
        }
        System.err.println();
        if (!found) {
            System.err.println("Timeout, bye!");
            System.exit(0);
        }
    }

    private static void waitForCardAbsent(CardTerminal t) throws CardException {
        boolean found = false;
        for (int i = 20; i > 0 && !found; i--) {
            found = t.waitForCardAbsent(3000); // Wait for a minute in 3 second rounds
            if (!found)
                System.err.println("Remove card and touch again");
        }
        System.err.println();
        if (!found) {
            System.err.println("Timeout, bye!");
            System.exit(0);
        }
    }
}
