/*
 * Copyright (c) 2014-2019 Martin Paljak
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
package apdu4j;

import apdu4j.i.BIBOProvider;
import apdu4j.i.BIBOServiceProvider;
import apdu4j.p.CardTerminalServiceProvider;
import apdu4j.p.CardTerminalTouchApplicationProvider;
import apdu4j.p.TouchTerminalApplication;
import apdu4j.terminals.LoggingCardTerminal;
import jnasmartcardio.Smartcardio;
import picocli.CommandLine;
import picocli.CommandLine.*;

import javax.smartcardio.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.Security;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "apdu4j", sortOptions = false)
public class NewSCTool implements Callable<Integer> {
    @Option(names = {"-v", "--verbose"}, description = "Be verbose. Add more for more verbose")
    boolean[] verboses = new boolean[0];
    boolean verbose;

    @Option(names = {"-l", "--list"}, description = "List readers.")
    boolean listReaders;
    @Option(names = {"-r", "--reader"}, description = "Use reader.")
    String readerName;
    @Option(names = {"-s", "--service"}, description = "Run service.", paramLabel = "<service>")
    String service;
    @Option(names = {"-S", "--touch-service"}, description = "Run service repeatedly.", paramLabel = "<service>")
    String touchService;
    @Option(names = {"-a", "--apdu"}, description = "Send APDU.", paramLabel = "<HEX>")
    byte[][] apdu;
    @ArgGroup(heading = "Protocol selection (default is T=*)%n")
    T0T1 proto = new T0T1();

    static class T0T1 {
        @Option(names = {"-t0"}, description = "Use T=0")
        boolean t0;
        @Option(names = {"-t1"}, description = "Use T=1")
        boolean t1;
    }

    @ArgGroup(heading = "Low level options%n", validate = false)
    LowLevel lowlevel = new LowLevel();

    static class LowLevel {
        @Option(names = {"-X", "--exclusive"}, description = "Use EXCLUSIVE mode (JNA only)")
        boolean exclusive;
        @Option(names = "--sun", description = "Use SunPCSC instead of JNA")
        boolean useSUN;
        @Option(names = "--no-get-response", description = "Do not use GET RESPONSE")
        boolean noGetResponse;
        @Option(names = {"-P", "--list-providers"}, description = "List providers")
        boolean listProviders;
        @Option(names = {"--lib"}, description = "Use PC/SC library with SunPCSC")
        String library;
    }

    static void configureLogging() {
        // Set up slf4j simple in a way that pleases us
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "true");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
        System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true");
        // Default level
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    public static void main(String[] args) {
        configureLogging();
        // Parse CLI
        NewSCTool tool = new NewSCTool();
        CommandLine cli = new CommandLine(tool);
        cli.registerConverter(byte[].class, s -> HexUtils.hex2bin(s));
        try {
            ParseResult r = cli.parseArgs(args);
            // Before we initialize users, like Plug
            if (tool.verboses.length > 2)
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace");
            if (tool.verboses.length > 1)
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
            if (tool.verboses.length > 0)
                tool.verbose = true;
        } catch (ParameterException ex) { // command line arguments could not be parsed
            System.err.println(ex.getMessage());
            ex.getCommandLine().usage(System.err);
            System.exit(1);
        }
        // Verifies the signature
        System.setSecurityManager(new Plug());

        // We always bundle JNA with the tool, so add it to the providers.
        Security.addProvider(new jnasmartcardio.Smartcardio());

        // Run program
        cli.execute(args);
    }

    @Override
    @SuppressWarnings("deprecation") // Provider.getVersion()
    public Integer call() {
        // Shorthand

        try {
            // List TerminalFactory providers
            if (lowlevel.listProviders) {
                Provider providers[] = Security.getProviders("TerminalFactory.PC/SC");
                System.out.println("Existing TerminalFactory providers:");
                if (providers != null) {
                    for (Provider p : providers) {
                        System.out.printf("%s v%s (%s) from %s%n", p.getName(), p.getVersion(), p.getInfo(), Plug.pluginfile(p));
                    }
                }
                // List all plugins
                for (Class<?> p : Arrays.asList(BIBOProvider.class, BIBOServiceProvider.class, CardTerminalServiceProvider.class, CardTerminalTouchApplicationProvider.class)) {
                    System.out.printf("Plugins for %s%n", p.getCanonicalName());
                    Plug.listPlugins(p);
                }
            }
            // Don't issue APDU-s internally
            if (lowlevel.noGetResponse) {
                System.setProperty("sun.security.smartcardio.t0GetResponse", "false");
                System.setProperty("sun.security.smartcardio.t1GetResponse", "false");
                System.setProperty("jnasmartcardio.transparent", "true");
            }

            final TerminalFactory tf;
            CardTerminals terminals = null;

            // Separate trycatch block for potential Windows exception in terminal listing
            try {
                if (lowlevel.useSUN) {
                    // Fix (SunPCSC) properties on non-windows platforms
                    TerminalManager.fixPlatformPaths();
                    // Override PC/SC library path (Only applies to SunPCSC)
                    if (lowlevel.library != null) {
                        System.setProperty("sun.security.smartcardio.library", lowlevel.library);
                    }
                    tf = TerminalFactory.getDefault();
                    if (System.getProperty(TerminalManager.LIB_PROP) != null && verbose) {
                        System.out.println("# " + TerminalManager.LIB_PROP + "=" + System.getProperty(TerminalManager.LIB_PROP));
                    }
                } else {
                    tf = TerminalManager.getTerminalFactory();
                }

                if (verbose) {
                    System.out.println("# Using " + tf.getProvider().getClass().getCanonicalName() + " - " + tf.getProvider());
                }
                // Get all terminals. This can throw
                terminals = tf.terminals();
            } catch (Smartcardio.EstablishContextException e) {
                String msg = TerminalManager.getExceptionMessage(e);
                fail("No readers: " + msg);
            }

            // Terminal to work on
            Optional<CardTerminal> reader = Optional.empty();

            try {
                // List terminals
                if (listReaders) {
                    List<CardTerminal> terms = terminals.list();
                    if (verbose) {
                        System.out.println("# Found " + terms.size() + " terminal" + (terms.size() == 1 ? "" : "s"));
                    }
                    if (terms.size() == 0) {
                        fail("No readers found");
                    }
                    for (CardTerminal t : terms) {
                        if (verbose)
                            t = LoggingCardTerminal.getInstance(t);
                        String vmd = " ";
                        if (verbose) {
                            try (PinPadTerminal pp = PinPadTerminal.getInstance(t)) {
                                pp.probe();
                                // Verify, Modify, Display
                                vmd += "[";
                                vmd += pp.canVerify() ? "V" : " ";
                                vmd += pp.canModify() ? "M" : " ";
                                vmd += pp.hasDisplay() ? "D" : " ";
                                vmd += "] ";
                            } catch (CardException e) {
                                String err = TerminalManager.getExceptionMessage(e);
                                if (err.equals(SCard.SCARD_E_SHARING_VIOLATION)) {
                                    vmd = " [   ] ";
                                } else
                                    vmd = " [EEE] ";
                            }
                        }
                        String present = t.isCardPresent() ? "[*]" : "[ ]";
                        String secondline = null;
                        String thirdline = null;
                        String filler = "          ";
                        if (verbose && t.isCardPresent()) {
                            Card c = null;
                            byte[] atr = null;
                            // Try shared mode, to detect exclusive mode via exception
                            try {
                                c = t.connect("*");
                                atr = c.getATR().getBytes();
                            } catch (CardException e) {
                                String err = TerminalManager.getExceptionMessage(e);
                                // Detect exclusive mode. Hopes this always succeeds
                                if (err.equals(SCard.SCARD_E_SHARING_VIOLATION)) {
                                    present = "[X]";
                                    try {
                                        c = t.connect("DIRECT");
                                        atr = c.getATR().getBytes();
                                    } catch (CardException e2) {
                                        String err2 = TerminalManager.getExceptionMessage(e2);
                                        if (err2.equals(SCard.SCARD_E_SHARING_VIOLATION)) {
                                            present = "[X]";
                                        }
                                    }
                                } else {
                                    secondline = err;
                                }
                            } finally {
                                if (c != null)
                                    c.disconnect(false);
                            }

                            if (atr != null) {
                                secondline = HexUtils.bin2hex(atr).toUpperCase();
                                if (ATRList.locate().isPresent() || System.getenv().containsKey("SMARTCARD_LIST")) {
                                    final ATRList atrList;
                                    if (System.getenv().containsKey("SMARTCARD_LIST")) {
                                        atrList = ATRList.from(System.getenv("SMARTCARD_LIST"));
                                    } else {
                                        atrList = ATRList.from(ATRList.locate().get());
                                    }
                                    Optional<Map.Entry<String, String>> desc = atrList.match(atr);
                                    if (desc.isPresent()) {
                                        thirdline = atrList.match(atr).get().getValue().replace("\n", filler);
                                    }
                                } else {
                                    thirdline = "https://smartcard-atr.appspot.com/parse?ATR=" + HexUtils.bin2hex(atr);
                                }
                            }
                        }

                        System.out.println(present + vmd + t.getName());
                        if (secondline != null)
                            System.out.println(filler + secondline);
                        if (thirdline != null)
                            System.out.println(filler + thirdline);
                    }
                }

                TerminalManager tm = TerminalManager.getInstance(TerminalManager.getTerminalFactory().terminals());
                // Select terminal to work on
                reader = tm.dwim(readerName, null, Collections.emptyList());
                if (reader.isPresent() && readerName != null) {
                    System.out.printf("Selected \"%s\" based on \"%s\"%n", reader.get().getName(), readerName);
                }
                if (!reader.isPresent()) {
                    if (readerName != null) { // DWIM failed
                        fail("Reader \"" + readerName + "\" not found.");
                    } else {
                        fail("Specify reader with -r");
                    }
                }
            } catch (CardException e) {
                // Address Windows with SunPCSC
                String em = TerminalManager.getExceptionMessage(e);
                if (em.equals(SCard.SCARD_E_NO_READERS_AVAILABLE)) {
                    fail("No reader with a card found!");
                } else {
                    System.err.println("Could not list readers: " + em);
                }
            }


            if (verbose) {
                FileOutputStream o = null;
                //if (args.has(OPT_DUMP)) {
                //    try {
                //        o = new FileOutputStream((File) args.valueOf(OPT_DUMP));
                //    } catch (FileNotFoundException e) {
                //        System.err.println("Can not dump to " + args.valueOf(OPT_DUMP));
                //    }
                //}
                reader = Optional.of(LoggingCardTerminal.getInstance(reader.get(), o));
            }

            // If we have meaningful work
            if (service == null && apdu == null && touchService == null)
                System.exit(0);

            // Do it
            work(reader.get());
        } catch (CardException e) {
            if (TerminalManager.getExceptionMessage(e) != null) {
                System.out.println("PC/SC failure: " + TerminalManager.getExceptionMessage(e));
            } else {
                System.out.printf("%s: %s", e.getClass().getName(), e.getMessage());
            }
        } catch (Exception e) {
            System.out.printf("%s: %s", e.getClass().getName(), e.getMessage());
            if (verbose)
                e.printStackTrace();
            System.exit(1);
        }
        return 0;
    }

    private String getProtocol() {
        String protocol;
        if (proto.t0) {
            protocol = "T=0";
        } else if (proto.t1) {
            protocol = "T=1";
        } else {
            protocol = "*";
        }

        // JNA-proprietary
        if (lowlevel.exclusive) {
            protocol = "EXCLUSIVE;" + protocol;
        } else if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            // Windows 8+ have the "5 seconds of transaction" limit. Because we want reliability
            // and don't have access to arbitrary SCard* calls via javax.smartcardio, we rely on
            // JNA interface and its EXCLUSIVE access instead and do NOT use the SCardBeginTransaction
            // capability of the JNA interface.
            // https://msdn.microsoft.com/en-us/library/windows/desktop/aa379469%28v=vs.85%29.aspx
            protocol = "EXCLUSIVE;" + protocol;
        }
        return protocol;
    }

    private void waitForCardOrExit(CardTerminal t) throws CardException {
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

    // Run a service repeatedly on a terminal
    private void repeat(CardTerminal reader, BIBOServiceProvider srvc) throws CardException {
        Thread cleanup = new Thread(() -> {
            System.err.println("\napdu4j is done");
        });
        Runtime.getRuntime().addShutdownHook(cleanup);

        try {
            while (!Thread.currentThread().isInterrupted()) {
                waitForCardOrExit(reader);
                final Card card;
                try {
                    card = reader.connect(getProtocol());
                } catch (CardException e) {
                    // If connect fails, it was probably removed from field
                    System.err.println("W: Too fast, try again!");
                    Thread.sleep(300); // to avoid instant re-powering
                    continue;
                }
                APDUBIBO bibo = new APDUBIBO(CardBIBO.wrap(card));
                // Run service
                srvc.get().apply(bibo);
                card.disconnect(true);

                int i = 0;
                boolean removed;
                // Wait until card is removed
                do {
                    removed = reader.waitForCardAbsent(1000);
                    System.out.print(".");
                    if (i >= 10 && i % 10 == 0) {
                        System.err.println("W: Remove card!");
                    }
                } while (removed == false && i++ < 60);
                System.out.println();
                // Final check. If card has not been removed, fail
                if (!removed) {
                    fail("E: Stuck card detected: " + reader.getName());
                }
                Thread.sleep(300); // to avoid re-powering
            }
        } catch (InterruptedException e) {
            System.err.println("Interrupted: " + e.getMessage());
        }
    }

    private void work(CardTerminal reader) throws CardException {
        if (touchService != null) {
            Optional<CardTerminalTouchApplicationProvider> touchApp = Plug.getPlugin(touchService, CardTerminalTouchApplicationProvider.class);
            if (touchApp.isPresent()) {
                TouchTerminalApplication app = touchApp.get().get(reader, getProtocol());
                try {
                    app.run();
                } catch (Exception e) {
                    System.err.printf("Application crashed: %s %s%n", e.getClass().getName(), e.getMessage());
                    if (verboses.length > 3)
                        e.printStackTrace();
                } finally {
                    return;
                }
            }
        } else {
            Card c = null;
            try {
                c = reader.connect(getProtocol());
                if (apdu != null) {
                    for (byte[] s : apdu) {
                        javax.smartcardio.CommandAPDU a = new javax.smartcardio.CommandAPDU(s);
                        javax.smartcardio.ResponseAPDU r = c.getBasicChannel().transmit(a);
                        if (r.getSW() != 0x9000) { // TODO: force
                            fail("Card returned " + String.format("%04X", r.getSW()) + ", exiting!");
                        }
                    }
                }
                if (service != null) {
                    // Locate plugin. First try terminal plugin
                    Optional<CardTerminalServiceProvider> pcscService = Plug.getPlugin(service, CardTerminalServiceProvider.class);
                    if (pcscService.isPresent()) {
                        System.exit(pcscService.get().get().apply(reader) ? 0 : 1);
                    }
                    // Then arbitrary BIBO
                    Optional<BIBOServiceProvider> biboService = Plug.getPlugin(service, BIBOServiceProvider.class);
                    if (biboService.isPresent()) {
                        System.exit(biboService.get().get().apply(CardBIBO.wrap(c)) ? 0 : 1);
                    }
                }
            } finally {
                if (c != null) {
                    c.disconnect(true);
                }
            }
        }
    }

    public static String getVersion() {
        String version = "unknown-development";
        try (InputStream versionfile = SCTool.class.getResourceAsStream("pro_version.txt")) {
            if (versionfile != null) {
                try (BufferedReader vinfo = new BufferedReader(new InputStreamReader(versionfile, StandardCharsets.UTF_8))) {
                    version = vinfo.readLine();
                }
            }
        } catch (IOException e) {
            version = "unknown-error";
        }
        return version;
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}