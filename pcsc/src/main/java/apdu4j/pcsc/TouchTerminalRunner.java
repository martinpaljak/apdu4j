/**
 * Copyright (c) 2014-2020 Martin Paljak
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package apdu4j.pcsc;

import apdu4j.core.TagRemovedException;
import apdu4j.core.TouchTerminalApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;

public final class TouchTerminalRunner {
    private static final Logger logger = LoggerFactory.getLogger(TouchTerminalRunner.class);

    public static int run(CardTerminal reader, TouchTerminalApp app, String[] args) {
        int r = app.onStart(args);
        if (r != 0)
            return r;
        String protocol = System.getProperty(CardTerminalApp.PROTOCOL_PROPERTY, "*");
        try {
            waitForCardAbsent(reader);

            while (!Thread.currentThread().isInterrupted()) {
                waitForCard(reader, 60);
                Card card;
                try {
                    card = reader.connect(protocol);
                } catch (CardException e) {
                    System.out.println(e.getMessage());
                    if (TerminalManager.getExceptionMessage(e).equals(SCard.SCARD_W_UNPOWERED_CARD)) {
                        // Contact card not yet powered up
                        Thread.sleep(100);
                        card = reader.connect(protocol);
                    } else {
                        System.err.println("W: Too fast, try again!");
                        Thread.sleep(300); // to avoid instant re-powering
                        continue;
                    }
                }
                try {
                    app.onTouch(CardBIBO.wrap(card));
                    card.disconnect(true);
                } catch (TagRemovedException e) {
                    logger.debug("Tag removed while onTouch");
                }
                int i = 0;
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
                    // possibly exit here
                }
                Thread.sleep(300); // to avoid re-powering
            }
        } catch (CardException e) {
            e.printStackTrace();
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            e.printStackTrace();
            return 1;
        }
        return 0;
    }


    public static boolean waitForCard(CardTerminal t, int seconds) throws CardException {
        logger.trace("Waiting for card...");
        final int dot = 3;
        final int n = seconds / dot;
        boolean found = false;
        System.err.format("%n[%s] Waiting for card ...", ReaderAliases.getDefault().translate(t.getName()));
        for (int i = 0; i < n && !found && !Thread.currentThread().isInterrupted(); i++) {
            found = t.waitForCardPresent(dot * 1000);
            System.err.print(".");
        }
        System.err.println();
        return found;
    }

    private static void waitForCardAbsent(CardTerminal t) throws CardException {
        logger.trace("Waiting for card removal...");
        boolean found = false;
        for (int i = 20; i > 0 && !found && !Thread.currentThread().isInterrupted(); i--) {
            found = t.waitForCardAbsent(3000); // Wait for a minute in 3 second rounds
            if (!found)
                System.err.println("Remove card and touch again");
        }
        System.err.println();
        if (!found) {
            System.err.println("Timeout, bye!");
        }
    }
}
