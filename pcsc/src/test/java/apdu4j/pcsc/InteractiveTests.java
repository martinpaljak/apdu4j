package apdu4j.pcsc;

import apdu4j.pcsc.terminals.LoggingCardTerminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import javax.smartcardio.*;
import javax.swing.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class InteractiveTests {
    static boolean sun = true;

    static {
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "true");
        System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss:SSS");
    }

    static final Logger log = LoggerFactory.getLogger(InteractiveTests.class);

    static boolean skipAll = false;
    static boolean isIDEA = System.getProperty("java.class.path", "").contains("idea_rt.jar");

    final static TerminalFactory factory;

    static {
        try {
            if (sun) {
                TerminalManager.fixPlatformPaths();
                factory = TerminalFactory.getDefault();
            } else {
                factory = TerminalManager.getTerminalFactory();
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException("No idea");
        }
    }

    static void skip(String message) {
        log.warn("Skipping: {}", message);
        throw new SkipException(message);
    }

    static void onlyInteractive(String message) {
        if (!isIDEA)
            skip("Not in IDEA");
        if (skipAll)
            skip("All tests skipped");

        int i = JOptionPane.showConfirmDialog(null, message, "Manual test instruction", JOptionPane.OK_CANCEL_OPTION);

        if (i == -1) {
            skip("Test skipped from GUI");
        }
        if (i == 2 || i == 1) {
            skipAll = true;
            skip("Manual cancel of all tests");
        }
    }


    boolean isSUN(Object o) {
        if (o.getClass().getCanonicalName().equals("sun.security.smartcardio.PCSCTerminals"))
            return true;
        return false;
    }

    @Test
    public void testTerminalsCancelling() throws Exception {
        onlyInteractive("Remove all readers");
        log.info("Creating listener");
        CardTerminals terminals = factory.terminals();

        log.info("Testing {}", terminals.getClass().getCanonicalName());

        PCSCMonitor monitor = new PCSCMonitor() {

            volatile List<PCSCReader> prev = new ArrayList<>();

            @Override
            public void readerListChanged(List<PCSCReader> states) {
                if (prev.size() == 0)
                    log.info("Initial list");
                Set<PCSCReader> prevSet = new HashSet<>(prev);
                Set<PCSCReader> newSet = new HashSet<>(states);
                if (states.size() < prev.size()) {
                    prevSet.removeAll(newSet);
                    log.info("Removed: {}", prevSet);
                } else if (states.size() > prev.size()) {
                    newSet.removeAll(prevSet);
                    log.info("Added: {}", newSet);
                } else {
                    log.info("No change");
                }
                prev = states;
            }

            @Override
            public void readerListErrored(Throwable t) {
                log.error("Errored!", t);
            }
        };

        HandyTerminalsMonitor watcher = new HandyTerminalsMonitor(factory, monitor);
        Thread thread = new Thread(watcher);
        thread.setName("PC/SC Monitor");
        thread.setDaemon(true);
        thread.start();
        log.info("Sleeping in main thread for 20sec");
        log.info("Plug in a reader");
        TimeUnit.SECONDS.sleep(20);
        log.info("Cancelling in main thread");
        thread.interrupt();
        thread.join(); // this should throw?
        log.info("Monitor joined");
        Assert.assertFalse(thread.isAlive());

    }

    @Test
    public void testTransactions() throws Exception {
        onlyInteractive("Have a single reader with a card in it");
        // TODO JNA: card disconnected in other thread should go IllegalstateException when transmitting in other thread
        log.info("Doing something");

        CardTerminals terminals = factory.terminals();
        List<CardTerminal> list = terminals.list();
        if (list.size() != 1) skip("Must have one reader: " + list.size());
        CardTerminal rdr = list.get(0);

        final CardTerminal reader = LoggingCardTerminal.getInstance(rdr);
        if (!reader.isCardPresent())
            skip("Must have card in reader");

        final Card c = reader.connect("*");
        final CardChannel ch = c.getBasicChannel();
        final CommandAPDU cmd = new CommandAPDU(0x00, 0xa4, 0x04, 0x00, 0x00);
        Thread thread = new Thread("juta") {
            @Override
            public void run() {
                try {
                    log.info("Thread started, waiting for absent");
                    if (reader.waitForCardAbsent(50000)) {
                        log.info("removed!");
                        return;
                    }
                    log.info("transmitting");
                    ch.transmit(cmd);
                    log.info("transmitted");
                } catch (CardException e) {
                    e.printStackTrace();
                }
            }
        };

        thread.start();
        Thread.sleep(100); // let thread start
        //c.beginExclusive();
        log.info("transmitting");
        ch.transmit(cmd);
        log.info("transmitted");
        TimeUnit.SECONDS.sleep(7);
        log.info("transmitting2");
        ch.transmit(cmd);
        log.info("transmitted2");
    }
}
