/*
 * Copyright (c) 2014-present Martin Paljak <martin@martinpaljak.net>
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
package apdu4j.tool;

import apdu4j.apdulette.Cookbook;
import apdu4j.apdulette.KitchenDisaster;
import apdu4j.apdulette.Recipe;
import apdu4j.apdulette.SousChef;
import apdu4j.core.APDUBIBO;
import apdu4j.core.BIBOException;
import apdu4j.core.HexUtils;
import apdu4j.core.ResponseAPDU;
import apdu4j.pcsc.*;
import apdu4j.prefs.Preference;
import apdu4j.prefs.Preferences;
import jnasmartcardio.Smartcardio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;

import javax.smartcardio.CardException;
import javax.smartcardio.TerminalFactory;
import java.io.IOException;
import java.io.PrintStream;
import java.security.Provider;
import java.security.Security;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

@Command(name = "apdu4j", versionProvider = SCTool.class, mixinStandardHelpOptions = true, subcommands = {HelpCommand.class})
public class SCTool implements Callable<Integer>, IVersionProvider {
    public static final String ENV_APDU4J_DEBUG = "APDU4J_DEBUG";
    public static final String ENV_APDU4J_DEBUG_FILE = "APDU4J_DEBUG_FILE";
    public static final String ENV_SMARTCARD_LIST = "SMARTCARD_LIST";

    // apdu4j tool's reader selection preferences (apdu4j.reader -> APDU4J_READER env var)
    public static final Preference.Default<String> READER =
            Preference.of("apdu4j.reader", String.class, "", false);
    public static final Preference.Default<String> READER_IGNORE =
            Preference.of("apdu4j.reader.ignore", String.class, "", false);

    final Logger logger = LoggerFactory.getLogger(SCTool.class);
    @Option(names = {"-v", "--verbose"}, description = "Be verbose")
    boolean verbose;
    @Option(names = {"-d", "--debug"}, description = "Trace APDU-s")
    boolean debug;
    @Option(names = {"-f", "--force"}, description = "Force, don't stop on errors")
    boolean force;
    @Option(names = {"-l", "--list"}, description = "List readers")
    boolean list;
    @Option(names = {"-W", "--no-wait"}, description = "Don't wait for card before running app")
    boolean noWait;
    @Option(names = {"-B", "--bare-bibo"}, description = "Don't handle 61XX/6CXX")
    boolean bareBibo;
    @Option(names = {"-r", "--reader"}, description = "Use reader", paramLabel = "<reader>")
    String reader;
    @Option(names = {"-R"}, description = "Force reader selector")
    boolean forceReaderSelection;

    @Option(names = {"-a", "--apdu"}, description = "Send APDU-s", paramLabel = "<HEX>")
    byte[][] apdus = {};

    @Parameters
    String[] params = {};

    @ArgGroup(heading = "Protocol selection (default is T=*)%n")
    T0T1 proto = new T0T1();

    static class T0T1 {
        @Option(names = {"--t0"}, description = "Use T=0")
        boolean t0;
        @Option(names = {"--t1"}, description = "Use T=1")
        boolean t1;
    }

    @ArgGroup(heading = "Low level options%n", validate = false)
    LowLevel lowlevel = new LowLevel();

    private TerminalManager manager;

    static class LowLevel {
        @Option(names = {"-X", "--exclusive"}, description = "Use EXCLUSIVE mode (JNA only)")
        boolean exclusive;
        @Option(names = {"-S", "--sun"}, description = "Use SunPCSC instead of JNA", arity = "0..1", paramLabel = "<lib>", fallbackValue = "")
        String useSUN;
    }

    private void verbose(String s) {
        if (verbose) {
            System.out.println("# " + s);
        }
    }

    void printReaderList(List<PCSCReader> readers, PrintStream to, boolean verbose) {
        if (readers.size() == 0) {
            to.println("No readers found");
            return;
        }
        var aliases = ReaderAliases.getDefault().apply(readers.stream().map(PCSCReader::name).toList());

        ATRList atrList = null;
        var atrListPath = Optional.ofNullable(System.getenv(ENV_SMARTCARD_LIST)).or(ATRList::locate);
        var hasSomeATR = readers.stream().filter(PCSCReader::present).count() > 0;
        if (atrListPath.isPresent() && hasSomeATR) {
            try {
                atrList = ATRList.from(atrListPath.get());
            } catch (IOException e) {
                logger.warn("Could not load ATR list: {}", e.getMessage(), e);
            }
        }
        var i = 0;
        String filler = readers.size() > 10 ? "              " : "             ";
        if (atrList != null && verbose) {
            verbose("ATR info from " + atrList.getSource().orElse("unknown source"));
        }
        for (PCSCReader r : readers) {
            i++;
            var vmdString = r.getVMD().map("[%s] "::formatted).orElse("");
            char marker = verbose ? PCSCReader.presenceMarker(r) : (r.present() ? '*' : ' ');
            to.println("%d: [%c] %s%s".formatted(i, marker, vmdString, aliases.extended(r.name())));
            if (verbose) {
                if (r.getATR().isPresent()) {
                    var atr = r.getATR().get();
                    to.println("%s%s".formatted(filler, HexUtils.bin2hex(atr)));
                    if (atrList != null) {
                        var desc = atrList.match(atr);
                        if (desc.isPresent()) {
                            desc.get().getValue().stream().forEachOrdered(l -> to.printf("%s- %s%n", filler, l));
                        }
                    } else {
                        to.printf("%shttps://smartcard-atr.apdu.fr/parse?ATR=%s%n", filler, HexUtils.bin2hex(atr));
                    }
                }
            }
        }
    }

    @Command(name = "list", description = "List available smart card readers.", aliases = {"ls"})
    public int listReaders(@Option(names = {"-v", "--verbose"}) boolean verbose) {

        var beVerbose = this.verbose || verbose;
        if (beVerbose) {
            List<String> env = System.getenv().entrySet().stream().filter(e -> e.getKey().startsWith("APDU4J_")).map(e -> "%s=\"%s\"".formatted(e.getKey(), e.getValue())).toList();
            if (!env.isEmpty()) {
                verbose(String.join(" ", env));
            }
        }
        try {
            var result = TerminalManager.listPCSC(getTerminalManager().terminals().list(), debug ? System.out : null, beVerbose);
            var prefs = Preferences.fromEnvironment();
            var hint = prefs.get(READER);
            result = Readers.dwimify(result, hint.isEmpty() ? null : hint,
                    Readers.parseIgnoreHints(prefs.get(READER_IGNORE)));
            printReaderList(result, System.out, beVerbose);
        } catch (Smartcardio.EstablishContextException | CardException e) {
            String em = SCard.getExceptionMessage(e);
            if (SCard.SCARD_E_NO_SERVICE.equals(em)) {
                return fail("PC/SC service is not running: " + em);
            } else if (SCard.SCARD_E_NO_READERS_AVAILABLE.equals(em)) {
                // Address Windows with SunPCSC
                return fail("No readers found: " + em);
            } else {
                return fail("Could not list readers: " + em);
            }
        }
        return 0;
    }

    @Command(name = "apdu", description = "Send raw APDU-s (Bytes Out)")
    public int sendAPDU(@Parameters(paramLabel = "<hex>", arity = "1..*") List<byte[]> apdus) {
        var toCard = new ArrayList<byte[]>(Arrays.asList(this.apdus));
        toCard.addAll(apdus);
        try {
            var sel = selector();
            if (proto.t0) {
                sel = sel.protocol("T=0");
            } else if (proto.t1) {
                sel = sel.protocol("T=1");
            }
            if (lowlevel.exclusive) {
                sel = sel.exclusive();
            }
            if (bareBibo) {
                sel = sel.with(Readers.TRANSPARENT, true);
            }
            var finalSel = sel;
            return noWait
                    ? finalSel.run(bibo -> sendAll(bibo, toCard))
                    : finalSel.whenReady(bibo -> sendAll(bibo, toCard));
        } catch (BIBOException e) {
            return fail(e.getMessage());
        }
    }

    private int sendAll(APDUBIBO bibo, List<byte[]> apdus) {
        for (byte[] s : apdus) {
            var r = new ResponseAPDU(bibo.transceive(s));
            if (r.getSW() != 0x9000 && !force) {
                return fail("Card returned %04X, exiting!".formatted(r.getSW()));
            }
        }
        return 0;
    }

    @Command(name = "plugins", description = "List available plugins.")
    public int listPlugins() {
        // List TerminalFactory providers
        Provider[] providers = Security.getProviders("TerminalFactory.PC/SC");
        System.out.println("Existing TerminalFactory providers:");
        if (providers != null) {
            for (Provider p : providers) {
                System.out.printf("%s v%s (%s) from %s%n", p.getName(), p.getVersionStr(), p.getInfo(), pluginfile(p));
            }
        }
        return 0;
    }

    // Bridge CLI options (-r, -d, -R) and env vars to the fluent Readers API
    private ReaderSelector selector() {
        var mgr = getTerminalManager();
        var prefs = Preferences.fromEnvironment();
        if (forceReaderSelection) {
            try {
                var hint = reader != null ? reader : prefs.get(READER);
                var ignoreFragments = Readers.parseIgnoreHints(prefs.get(READER_IGNORE));
                var chosen = FancyChooser.forTerminals(mgr,
                        hint.isEmpty() ? null : hint, ignoreFragments).call();
                if (chosen.isEmpty()) {
                    exit("No reader selected");
                }
                var s = Readers.select(mgr, chosen.get().getName());
                return debug ? s.log(System.out) : s;
            } catch (Exception e) {
                throw new RuntimeException("Reader selection failed", e);
            }
        }
        var s = Readers.fromPreferences(mgr, prefs, READER, READER_IGNORE);
        if (reader != null) {
            s = s.select(reader);
        }
        if (debug) {
            s = s.log(System.out);
        }
        return s;
    }

    @Command(name = "uid", description = "Read card UID from each tap, fall back to ATR")
    public int uid() {
        var counter = new AtomicInteger();
        var exiter = new Thread(() -> System.out.printf("%n%nYou tapped %d cards. Cool!%n", counter.get()));
        Runtime.getRuntime().addShutdownHook(exiter);
        selector().fresh(true).onCard((reader, bibo) -> {
            var n = counter.incrementAndGet();
            var atr = reader.getATR().map(HexUtils::bin2hex).orElse("no ATR");
            var recipe = Cookbook.uid()
                    .map(uid -> "UID: " + HexUtils.bin2hex(uid))
                    .recover(err -> Recipe.premade("ATR: " + atr));
            System.out.printf("%d: %s %s%n", n, reader.name(), new SousChef(bibo).cook(recipe));
        });
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    @Command(name = "atr", description = "Read card ATR via DIRECT protocol (no card reset)")
    public int atr() {
        try {
            var card = selector().protocol("DIRECT").card();
            try {
                System.out.println(HexUtils.bin2hex(card.getATR().getBytes()));
            } finally {
                card.disconnect(false);
            }
            return 0;
        } catch (BIBOException | CardException e) {
            return fail(e.getMessage());
        }
    }

    @Command(name = "cplc", description = "Read GlobalPlatform CPLC data")
    public int cplc() {
        var recipe = ToolCookbook.selectOpen()
                .and(ToolCookbook.cplc())
                .map(CPLC::fromBytes);
        try {
            var sel = selector();
            var cplc = noWait
                    ? sel.run(bibo -> new SousChef(bibo).cook(recipe))
                    : sel.whenReady(bibo -> new SousChef(bibo).cook(recipe));
            System.out.println(cplc.toPrettyString());
            return 0;
        } catch (KitchenDisaster e) {
            return fail("Card error: " + e.getMessage());
        } catch (BIBOException e) {
            return fail(e.getMessage());
        }
    }

    static void configureLogging() {
        // Set up slf4j simple in a way that pleases us
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "true");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
        System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true");

        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss:SSS");

        if (System.getenv(ENV_APDU4J_DEBUG) != null) {
            System.setProperty("org.slf4j.simpleLogger.logFile", System.getenv().getOrDefault(ENV_APDU4J_DEBUG_FILE, "apdu4j.log"));
        }
        // Default level
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", System.getenv().getOrDefault(ENV_APDU4J_DEBUG, "warn"));
    }

    public static void main(String[] args) {
        try {
            // Configure logging
            configureLogging();

            // Parse CLI
            var tool = new SCTool();
            var cli = new CommandLine(tool);

            // To support "sc gp -ldv"
            cli.setUnmatchedOptionsArePositionalParams(true);
            //cli.setStopAtUnmatched(true);
            cli.setStopAtPositional(true);
            cli.registerConverter(byte[].class, HexUtils::stringToBin);
            try {
                cli.parseArgs(args);
            } catch (ParameterException ex) { // command line arguments could not be parsed
                System.err.println(ex.getMessage());
                ex.getCommandLine().usage(System.err);
                System.exit(1);
            }

            // Run program
            cli.execute(args);
        } catch (RuntimeException e) {
            System.exit(fail("Error: " + e.getMessage()));
        }
    }

    @Override
    public Integer call() {
        if (list) {
            return listReaders(verbose);
        }
        if (apdus.length > 0) {
            return sendAPDU(Collections.emptyList());
        }
        if (params.length > 0) {
            if (params[0].startsWith("-")) {
                System.err.println("Invalid parameters: " + String.join(" ", params));
                return 1;
            }
            System.err.println("Unknown parameter: " + params[0]);
            return 66;
        }
        System.out.println("Nothing to do!");
        return 0;
    }

    Optional<String> forceLibraryPath() {
        if (lowlevel.useSUN != null && !lowlevel.useSUN.isBlank()) {
            return Optional.of(lowlevel.useSUN.trim());
        }
        return Optional.empty();
    }

    // Return a terminal factory manager, taking into account CLI options
    TerminalManager getTerminalManager() {
        TerminalFactory tf;
        if (manager != null) {
            return manager;
        }
        // Separate trycatch block for potential Windows exception in terminal listing
        try {
            if (lowlevel.useSUN != null) {
                // Fix (SunPCSC) properties on non-windows platforms
                if (TerminalManager.isEnabled("apdu4j.fixpath", true)) {
                    TerminalManager.fixPlatformPaths();
                }

                // Override PC/SC library path (Only applies to SunPCSC)
                forceLibraryPath().ifPresent(e -> System.setProperty(TerminalManager.LIB_PROP, e));

                // Log the library if verbose
                if (verbose && System.getProperty(TerminalManager.LIB_PROP) != null) {
                    System.out.println("# " + TerminalManager.LIB_PROP + "=" + System.getProperty(TerminalManager.LIB_PROP));
                }
                // Get the built-in provider
                tf = TerminalFactory.getDefault();
                if (TerminalManager.isNoneProvider(tf)) {
                    fail("PC/SC is not available via SunPCSC. Install pcsc-lite or check smart card service.");
                    return null;
                }
            } else {
                // Get JNA or default
                tf = TerminalManager.getTerminalFactory();
            }

            if (verbose) {
                System.out.println("# Using " + tf.getProvider());
            }
            manager = new TerminalManager(tf);
            return manager;
        } catch (Smartcardio.EstablishContextException e) {
            String msg = SCard.getExceptionMessage(e);
            fail("No readers: " + msg);
            return null;
        } catch (IllegalStateException e) {
            fail(e.getMessage());
            return null;
        }
    }

    @Override
    public String[] getVersion() {
        var secondLine = "# Running on %s %s %s".formatted(System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"))
                + ", Java %s by %s".formatted(System.getProperty("java.version"), System.getProperty("java.vendor"));

        var v = new ArrayList<String>();
        v.add(TerminalManager.getVersion());
        v.add(secondLine);
        List<String> env = System.getenv().entrySet().stream().filter(e -> e.getKey().startsWith("APDU4J_")).map(e -> "%s=\"%s\"".formatted(e.getKey(), e.getValue())).toList();
        if (!env.isEmpty()) {
            v.add("# " + String.join(" ", env));
        }
        return v.toArray(new String[0]);
    }

    private static String pluginfile(Object o) {
        var src = o.getClass().getProtectionDomain().getCodeSource();
        if (src == null) {
            return "builtin";
        }
        var url = src.getLocation();
        if ("file".equals(url.getProtocol())) {
            return url.getFile();
        }
        return url.toExternalForm();
    }

    private static int fail(String message) {
        System.err.println(message);
        return 1;
    }

    private static void exit(String message) {
        System.exit(fail(message));
    }
}
