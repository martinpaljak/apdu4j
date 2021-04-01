/**
 * Copyright (c) 2020-present Martin Paljak
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminals;
import javax.smartcardio.TerminalFactory;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

// Take care of the PC/SC weirdnesses and notify of reader list changes as plain data
// This SHOULD be run in a daemon thread, or interrupts will work in TICK_WAIT intervals
public final class HandyTerminalsMonitor implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(HandyTerminalsMonitor.class);

    private final static long TICK_WAIT = 3000; // PC/SC wait time, for interrupt() to work before infinity
    private final static long TICK_POLL = 1000; // Thread sleep time

    private final PCSCMonitor listener;

    private TerminalManager manager;
    private CardTerminals monitor;
    private boolean isSunPCSC = false;

    public HandyTerminalsMonitor(TerminalManager whatToMonitor, PCSCMonitor whereToReport) {
        this.manager = whatToMonitor;
        this.listener = whereToReport;
    }

    private final boolean isWindows = TerminalManager.isWindows();
    private final boolean isMacOS = TerminalManager.isMacOS();
    private final boolean isLinux = !(isWindows || isMacOS);

    private Set<PCSCReader> currentState = Collections.emptySet();
    private boolean haveReportedNoReaders = false;

    /**
     * With pcsc-lite, every thread requires their own context, or blocking calls would block other threads
     * using the same context. Thus we MUST get a fresh context in the thread.
     * <p>
     * JNA implementation creates a context when terminals() is called. This allows to work even after
     * SCARD_E_SERVICE_STOPPED is received, what would block SunPCSC.
     */
    private void establishContext() {
        try {
            logger.info("Getting new terminals object");
            monitor = manager.terminals(true);
            String monitorClass = monitor.getClass().getCanonicalName();
            if (monitorClass.equals("javax.smartcardio.TerminalFactory.NoneCardTerminals")) {
                // we can't recover from this
                fail("SunPCSC without a valid module? Please restart the application!", null);
            } else if (monitorClass.equals("sun.security.smartcardio.PCSCTerminals")) {
                isSunPCSC = true;
                logger.info("SunPCSC mode");
            } else if (monitorClass.equals("jnasmartcardio.Smartcardio.JnaCardTerminals")) {
                logger.info("jnasmartcardio mode");
            } else {
                logger.warn("Unknown CardTerminals class {} ", monitorClass);
            }
        } catch (Exception e) {
            // What to do here, report error and fail? Try some recovery?
            logger.error("Failed to fetch terminals: " + e.getMessage(), e);
            e.printStackTrace();
        }
    }


    private void fail(String message, Throwable t) {
        logger.error("Failing: {} {}", message, t == null ? "" : SCard.getExceptionMessage(t));
        Throwable ex = (t == null ? new IllegalStateException(message) : new IllegalStateException(message, t));
        listener.readerListErrored(ex);
        Thread.currentThread().interrupt();
    }


    private boolean shouldReport(List<PCSCReader> newStates) {
        HashSet<PCSCReader> news = new HashSet<>(newStates);
        logger.debug("current state: {}", currentState);
        logger.debug("new     state: {}", news);

        // Only report no readers once
        if (news.size() == 0 && !haveReportedNoReaders) {
            return true;
        } else {
            if (!currentState.equals(news)) {
                logger.debug("change");
                return true;
            } else {
                logger.debug("no change");
            }
        }
        // "supurious state change" from jna
        return false;
    }

    private void reportChanges(List<PCSCReader> state) {
        logger.debug("Reporting state: {}", state);
        haveReportedNoReaders = state.size() == 0;
        listener.readerListChanged(state);
        currentState = new HashSet<>(state);
    }

    @Override
    public void run() {
        logger.debug("PC/SC monitor thread starting");
        establishContext();
        // SunPCSC does not detect a change on initial wait so
        // we always list first before waiting and then filter JNA "spurious changes"
        boolean changed = true; // Trigger initial listing
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // list readers and their changes, reporting changes as necessary
                if (!Thread.currentThread().isInterrupted() && changed) {
                    try {
                        long start = System.currentTimeMillis();
                        List<PCSCReader> readers = TerminalManager.listPCSC(monitor.list(), null, false);
                        logger.debug("list took {}ms, {} items", System.currentTimeMillis() - start, readers.size());

                        if (shouldReport(readers))
                            reportChanges(readers);

                        if (isSunPCSC && readers.size() == 0) {
                            //Exception in thread "PC/SC Monitor" java.lang.IllegalStateException: No terminals available
                            //	at java.smartcardio/sun.security.smartcardio.PCSCTerminals.waitForChange(PCSCTerminals.java:174)
                            logger.info("sunpcsc on macosx, waitForChange() will fail with IllegalStateException");
                            Thread.sleep(TICK_POLL);
                            continue;
                        }
                    } catch (CardException e) {
                        String err = SCard.getExceptionMessage(e);
                        // pcsc-lite
                        if (err.equals(SCard.SCARD_E_NO_READERS_AVAILABLE)) {
                            if (isLinux) {
                                logger.info("No readers, sleeping one tick");
                                TimeUnit.MILLISECONDS.sleep(TICK_POLL);
                                continue;
                            } else {
                                fail("list", e);
                            }
                        } else if (err.equals(SCard.SCARD_E_SERVICE_STOPPED)) {
                            if (isWindows) {
                                if (isSunPCSC) {
                                    fail("Can't recover from stopped PC/SC with SunPCSC", e);
                                } else {
                                    logger.info("Getting new context");
                                    establishContext();
                                    TimeUnit.MILLISECONDS.sleep(TICK_POLL);
                                    continue;
                                }
                            } else {
                                // Should not happen on non-windows
                                fail("list", e);
                            }
                        } else if (err.equals(SCard.SCARD_E_NO_SERVICE)) {
                            // SunPCSC on Windows throws after reader is removed and service stopped has been received
                            logger.info("list: {}", err);
                            TimeUnit.MILLISECONDS.sleep(TICK_POLL);
                            continue;
                        } else {
                            fail("list", e);
                        }
                    }
                }
                // ... and wait for further changes
                if (!Thread.currentThread().isInterrupted()) {
                    try {
                        long start = System.currentTimeMillis();
                        changed = monitor.waitForChange(TICK_WAIT);
                        logger.debug("wait took {}ms and was {}", System.currentTimeMillis() - start, changed);
                    } catch (CardException e) {
                        String err = SCard.getExceptionMessage(e);
                        // Removing a reader on Linux results in timeout error, adding results in true
                        if (err.equals(SCard.SCARD_E_TIMEOUT)) {
                            logger.info("wait: {}", err);
                            if (isLinux) {
                                logger.debug("Removed reader on Linux");
                                changed = true;
                            } else {
                                fail("wait", e);
                            }
                        } else if (err.equals(SCard.SCARD_E_SERVICE_STOPPED)) {
                            // After reader is removed on Windows, service is stopped. New CardTerminals needs to be fetched
                            logger.info("wait: {}", err);
                            TimeUnit.MILLISECONDS.sleep(TICK_POLL);
                            continue;
                        } else if (err.equals(SCard.SCARD_E_NO_READERS_AVAILABLE)) {
                            // SunPCSC on Linux
                            if (isLinux && isSunPCSC) {
                                // Should have been caught in list cycle
                                logger.error("{} in wait cycle? Should not reach here");
                                // Anyway, wait a bit before hitting it again
                                TimeUnit.MILLISECONDS.sleep(TICK_POLL);
                                continue;
                            } else {
                                fail("list", e);
                            }
                        } else {
                            fail("wait", e);
                        }
                    }
                }
            }
            logger.debug("Thread interrupted cleanly, done");
        } catch (InterruptedException e) {
            logger.debug("Thread was interrupted, done");
        }
    }
}
