package apdu4j.pcsc;

import apdu4j.core.AsynchronousBIBO;
import apdu4j.core.BIBOException;
import apdu4j.core.HexUtils;
import apdu4j.core.SmartCardAppListener;
import apdu4j.core.SmartCardAppListener.AppParameters;
import apdu4j.core.SmartCardAppListener.CardData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import java.io.EOFException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static apdu4j.pcsc.TerminalManager.getExceptionMessage;

/**
 * Runs an application on top of a CardTerminal. This is usually run in a thread.
 */
public class CardTerminalAppRunner implements Runnable, AsynchronousBIBO {
    private static final Logger logger = LoggerFactory.getLogger(CardTerminalAppRunner.class);
    private static final long IDLE_TIMEOUT_SECONDS = 60;

    final CardTerminal terminal;
    final SmartCardAppListener app;

    private AtomicReference<CompletableFuture<byte[]>> incoming = new AtomicReference<>();
    private AtomicReference<CompletableFuture<byte[]>> outgoing = new AtomicReference<>();

    private CompletableFuture<AppParameters> start = new CompletableFuture<>();

    String protocol = "*";
    boolean multisession = false;
    boolean needsTouch = true;

    // Companion thread for monitoring events
    Thread monitor;

    public CardTerminalAppRunner(CardTerminal terminal, SmartCardAppListener app, String protocol, boolean multisession, boolean needsTouch) {
        this(terminal, app);
        this.multisession = multisession;
        this.needsTouch = needsTouch;
        this.protocol = protocol;
    }

    CardTerminalAppRunner(CardTerminal terminal, SmartCardAppListener app) {
        this.terminal = terminal;
        this.app = app;
        logger.info("Created connector for {} on {}", app.getClass(), terminal.getName());
    }

    public static CardTerminalAppRunner once(CardTerminal terminal, SmartCardAppListener app) {
        CardTerminalAppRunner r = new CardTerminalAppRunner(terminal, app);
        r.multisession = false;
        r.needsTouch = false;
        return r;
    }

    public static CardTerminalAppRunner forever(CardTerminal terminal, SmartCardAppListener app) {
        CardTerminalAppRunner r = new CardTerminalAppRunner(terminal, app);
        r.multisession = true;
        r.needsTouch = true;
        return r;
    }

    @Override
    public void run() {
        // Only set if not main thread
        if (!Thread.currentThread().getName().equals("main"))
            Thread.currentThread().setName(terminal.getName());

        logger.info("Using " + terminal.getName());

        Card card = null;
        CardBIBO trunk = null;

        // wait for card, connect, send onCardPresent, spawn monitor, wait for apdu, transmit,
        CompletableFuture.runAsync(() -> app.onStart(start));
        try {
            // This blocks until available
            AppParameters appProperties = start.join();
            // Override parameters
            this.multisession = Boolean.parseBoolean(appProperties.getOrDefault(AppParameters.MULTISESSION_BOOLEAN, Boolean.toString(multisession)));
            this.needsTouch = Boolean.parseBoolean(appProperties.getOrDefault(AppParameters.TOUCH_REQUIRED_BOOLEAN, Boolean.toString(needsTouch)));
            this.protocol = appProperties.getOrDefault(AppParameters.PROTOCOL_STRING, protocol);

            logger.info("go-ahead received from app: {}", appProperties);
            freshcard:
            // This loop happens for multisession mode
            do {
                logger.debug("Waiting for a session start");
                try {
                    if (needsTouch)
                        waitForCardAbsent(terminal);
                    waitForCard(terminal);
                    logger.info("Card present");

                    try {
                        card = terminal.connect(protocol);

                        if (monitor == null) {
                            monitor = new MonitorThread(this, app);
                            monitor.start();
                        }
                        logger.debug("Connected card, protocol {}", card.getProtocol());
                    } catch (CardException e) {
                        logger.error(e.getMessage());
                        if (getExceptionMessage(e).equals(SCard.SCARD_W_UNPOWERED_CARD)) {
                            // Contact card not yet powered up
                            Thread.sleep(100);
                            card = terminal.connect(protocol);
                        } else if (getExceptionMessage(e).equals(SCard.SCARD_E_SHARING_VIOLATION)) {
                            logger.error("Terminal under exclusive mode by some other application, ending in error");
                            app.onError(e);
                            return;
                        } else {
                            logger.warn("W: Too fast, try again!");
                            Thread.sleep(300); // to avoid instant re-powering
                            continue;
                        }
                    }

                    // We have a card or token present
                    trunk = CardBIBO.wrap(card);

                    CardData props = new CardData();
                    props.put("atr", HexUtils.bin2hex(card.getATR().getBytes()));
                    props.put("protocol", card.getProtocol());
                    props.put("reader", terminal.getName());

                    incoming.set(new CompletableFuture<>());
                    CompletableFuture.runAsync(() -> app.onCardPresent(this, props));

                    while (!Thread.currentThread().isInterrupted()) {
                        boolean exceptioned = true;
                        try {
                            byte[] cmd = incoming.get().get(IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            byte[] response = trunk.transceive(cmd);
                            // Assumes outgoing is always set by transmit()
                            outgoing.get().complete(response);
                            exceptioned = false;
                        } catch (BIBOException e) {
                            logger.info("Transmit failed: " + e.getCause());
                            outgoing.get().completeExceptionally(e);
                        } catch (TimeoutException e) {
                            logger.error("App timed out and did not send anything within {} seconds", IDLE_TIMEOUT_SECONDS);
                            CompletableFuture.runAsync(() -> app.onError(e));
                            return;
                        } catch (ExecutionException e) {
                            logger.info("incoming.get() exceptioned: " + e.getCause());
                            // XXX: the exception thingy here is super hacky
                            if (e.getCause() instanceof EOFException) {
                                card.disconnect(true);
                            } else {
                                e.printStackTrace();
                            }
                        } finally {
                            if (exceptioned) {
                                if (multisession)
                                    continue freshcard;
                                else
                                    return;
                            }
                        }
                    }
                } catch (CardException e) {
                    // Thrown by initial waitforcard* and the second connect
                    logger.warn("Exception: " + e.getMessage());
                    if (getExceptionMessage(e).equals(SCard.SCARD_E_SHARING_VIOLATION)) {
                        logger.error("Can't use reader", e);
                        app.onError(e);
                        return;
                    }
                    if (multisession)
                        continue;
                    else
                        return;
                } catch (InterruptedException e) {
                    logger.info("Reader thread interrupted, exiting");
                    CompletableFuture.runAsync(() -> app.onError(e));
                    Thread.currentThread().interrupt(); // set the flag, so that do/while would not continue
                }
            } while (!Thread.currentThread().isInterrupted() && multisession);
        } finally { // to make sure monitor gets interrupted
            if (monitor != null)
                monitor.interrupt();
        }
        logger.info("DONE; releasing reader");
    }

    // Thread that waits for card removal,
    // to report removal even if no APDU communication in place.
    static class MonitorThread extends Thread {
        final CardTerminalAppRunner t;
        final SmartCardAppListener app;

        MonitorThread(CardTerminalAppRunner t, SmartCardAppListener app) {
            this.t = t;
            this.app = app;
            setDaemon(true);
        }

        @Override
        public void run() {
            setName("monitor: " + t.terminal.getName());
            logger.info("Monitor thread starting");
            while (!isInterrupted()) {
                try {
                    // Wait for card removal
                    boolean found = t.terminal.waitForCardAbsent(3000);
                    if (found) {
                        logger.info("Card removed!");
                        // Do not call from monitor thread
                        CompletableFuture.runAsync(() -> app.onCardRemoved());
                        if (!t.multisession)
                            break;
                        else {
                            t.terminal.waitForCardPresent(0);
                        }
                    }
                } catch (CardException e) {
                    logger.warn("Failed: " + e.getMessage(), e);
                }
            }
            logger.info("Monitor thread done");
        }
    }


    public static void waitForCard(CardTerminal t) throws CardException {
        logger.trace("Waiting for card...");
        while (!Thread.currentThread().isInterrupted()) {
            if (t.waitForCardPresent(1000))
                return;
        }
    }

    private static void waitForCardAbsent(CardTerminal t) throws CardException {
        logger.trace("Waiting for card removal...");
        while (!Thread.currentThread().isInterrupted()) {
            if (t.waitForCardAbsent(3000))
                return;
        }
    }


    @Override
    public CompletableFuture<byte[]> transmit(byte[] command) {
        final CompletableFuture<byte[]> og = outgoing.get();
        final CompletableFuture<byte[]> ic = incoming.get();
        if (ic == null)
            return CompletableFuture.failedFuture(new IllegalStateException("We are not waiting for commands!"));
        if (og != null && !og.isDone())
            return CompletableFuture.failedFuture(new IllegalStateException("Last command not yet completed!"));
        CompletableFuture<byte[]> response = new CompletableFuture<>();
        CompletableFuture<byte[]> newIncoming = new CompletableFuture<>();

        if (outgoing.compareAndSet(og, response) && incoming.compareAndSet(ic, newIncoming)) {
            ic.complete(command);
            // Response will be completed when the command is sent.
            return response;
        } else {
            IllegalStateException ex = new IllegalStateException("incoming and outgoing mismatch!");
            og.completeExceptionally(ex);
            ic.completeExceptionally(ex);
            return CompletableFuture.failedFuture(ex);
        }
    }

    @Override
    public void close() {
        logger.info("close()");
        CompletableFuture ic = incoming.get();
        if (ic != null)
            ic.completeExceptionally(new EOFException("Done"));
    }
}