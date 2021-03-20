/*
 * Copyright (c) 2020-present Martin Paljak
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package apdu4j.pcsc;

import apdu4j.core.AsynchronousBIBO;
import apdu4j.core.BIBOException;
import apdu4j.core.SmartCardAppListener;
import apdu4j.core.SmartCardAppListener.AppParameters;
import apdu4j.core.SmartCardAppListener.CardData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.*;
import java.io.EOFException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static apdu4j.pcsc.SCard.getExceptionMessage;

/**
 * Runs an application on top of a CardTerminal. This is usually run in a thread.
 */
public class CardTerminalAppRunner implements Runnable, AsynchronousBIBO {
    private static final Logger logger = LoggerFactory.getLogger(CardTerminalAppRunner.class);
    private static final long IDLE_TIMEOUT_SECONDS = 60;

    private final String[] argv;

    final String terminalName;
    final TerminalFactory factory;
    final SmartCardAppListener app;

    private AtomicReference<CompletableFuture<byte[]>> incoming = new AtomicReference<>();
    private AtomicReference<CompletableFuture<byte[]>> outgoing = new AtomicReference<>();

    String protocol = "*";
    boolean multisession = false;
    boolean needsTouch = true;

    boolean spawnMonitor = true;

    // Companion thread for monitoring events
    Thread monitor;

    public CardTerminalAppRunner(TerminalFactory factory, String terminal, SmartCardAppListener app, String[] argv) {
        this.factory = factory;
        this.terminalName = terminal;
        this.app = app;
        this.argv = argv.clone();
        logger.info("Created connector for {} on {}", app.getClass(), terminalName);
    }

    public static CardTerminalAppRunner once(TerminalFactory factory, String terminalName, SmartCardAppListener app) {
        CardTerminalAppRunner r = new CardTerminalAppRunner(factory, terminalName, app, new String[0]);
        r.multisession = false;
        r.needsTouch = false;
        return r;
    }

    @Override
    public void run() {
        // Make sure we have our own (per-thread) PC/SC context on Linux
        CardTerminals terminals = factory.terminals();
        CardTerminal terminal = terminals.getTerminal(terminalName);

        // Only set if not main thread
        if (!Thread.currentThread().getName().equals("main"))
            Thread.currentThread().setName(terminal.getName());

        logger.info("Using " + terminal.getName());

        Card card = null;
        CardBIBO trunk = null;

        // wait for card, connect, send onCardPresent, spawn monitor, wait for apdu, transmit,
        CompletableFuture<AppParameters> start = app.onStart(argv);
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

                        if (monitor == null && spawnMonitor) {
                            monitor = new CardTerminalMonitorThread(this, app);
                            monitor.start();
                        }
                        logger.debug("Connected card, protocol {}", card.getProtocol());
                    } catch (CardException e) {
                        logger.error("Could not connect: {}", e.getMessage());
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
                    props.put(CardData.ATR_BYTES, card.getATR().getBytes());
                    props.put(CardData.PROTOCOL_STRING, card.getProtocol());
                    props.put("reader", terminal.getName());

                    incoming.set(new CompletableFuture<>());
                    // This makes sure that the initial thread for async tasks would NOT be the reader thread
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
                            String err = SCard.getExceptionMessage(e);
                            logger.info("Transmit failed: " + e.getCause());
                            // Trigger card removed event
                            app.onCardRemoved();
                            // And complete outstanding transmit exceptionally
                            outgoing.get().completeExceptionally(e);
                        } catch (TimeoutException e) {
                            logger.error("App timed out and did not send anything within {} seconds", IDLE_TIMEOUT_SECONDS);
                            app.onError(e);
                            //Thread.currentThread().interrupt(); // FIXME: we bail out? what to do when multisession?
                            return;
                        } catch (ExecutionException e) {
                            logger.info("incoming.get() exceptioned: " + e.getCause());
                            // XXX: the exception thingy here is super hacky
                            if (e.getCause() instanceof EOFException) {
                                card.disconnect(true);
                                if (multisession) {
                                    waitForCardAbsent(terminal);
                                    app.onCardRemoved();
                                }
                            } else {
                                e.printStackTrace();
                            }
                        } finally {
                            if (exceptioned) {
                                logger.debug("Transceive exceptioned");
                                if (multisession) {
                                    logger.debug("Continuing multisession app");
                                    continue freshcard;
                                } else {
                                    logger.debug("We are done");
                                    return;
                                }
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

    // Thread that waits for card removal, to emit removal event even if no APDU communication is in place.
    static class CardTerminalMonitorThread extends Thread {
        final CardTerminalAppRunner t;
        final SmartCardAppListener app;

        CardTerminalMonitorThread(CardTerminalAppRunner t, SmartCardAppListener app) {
            this.t = t;
            this.app = app;
            setDaemon(true);
        }

        @Override
        public void run() {
            setName("monitor: " + t.terminalName);
            logger.info("Monitor thread starting");
            final CardTerminal terminal;
            CardTerminals terminals = t.factory.terminals();
            terminal = terminals.getTerminal(t.terminalName);

            while (!isInterrupted()) {
                try {
                    // Wait for card removal
                    boolean found = terminal.waitForCardAbsent(0);
                    if (found) {
                        logger.info("Card removed!");
                        // Do not call from monitor thread
                        CompletableFuture.runAsync(() -> app.onCardRemoved());
                        if (!t.multisession)
                            break;
                        else {
                            terminal.waitForCardPresent(0);
                        }
                    }
                } catch (CardException e) {
                    // Errors means no event, but not fatal/relevant for app. Don't report error
                    logger.warn("Failed to wait: {}", SCard.getExceptionMessage(e), e);
                }
            }
            logger.info("Monitor thread done");
        }

    }


    public static void waitForCard(CardTerminal t) throws CardException, InterruptedException {
        logger.debug("Waiting for card...");
        while (true) {
            boolean result = t.waitForCardPresent(1000);
            logger.debug("result: {}", result);
            if (Thread.interrupted())
                throw new InterruptedException("interrupted");
            if (result)
                return;
        }
    }

    public static void waitForCardAbsent(CardTerminal t) throws CardException, InterruptedException {
        logger.debug("Waiting for card absent...");
        while (true) {
            boolean result = t.waitForCardAbsent(1000);
            logger.debug("result: {}", result);
            if (Thread.interrupted())
                throw new InterruptedException("interrupted");
            if (result)
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
        logger.debug("close()");
        CompletableFuture ic = incoming.get();
        if (ic != null)
            ic.completeExceptionally(new EOFException("Done with #close()"));
    }
}