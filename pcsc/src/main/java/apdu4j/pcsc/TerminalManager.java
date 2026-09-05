// SPDX-FileCopyrightText: 2014 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import apdu4j.core.BIBOException;
import apdu4j.pcsc.sim.SynthesizedCardTerminal;
import apdu4j.pcsc.sim.SynthesizedCardTerminals;
import apdu4j.pcsc.terminals.LoggingCardTerminal;
import jnasmartcardio.Smartcardio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.*;
import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Facilitates working with javax.smartcardio TerminalFactory/CardTerminals
 * <p>
 * Also knows about an alternative implementation, jnasmartcardio
 */
public final class TerminalManager implements PCSCMonitor, Closeable {
    private static final Logger logger = LoggerFactory.getLogger(TerminalManager.class);

    public static final String LIB_PROP = "sun.security.smartcardio.library";

    private static final String debian64_path = "/usr/lib/x86_64-linux-gnu/libpcsclite.so.1";
    private static final String ubuntu_path = "/lib/libpcsclite.so.1";
    private static final String ubuntu32_path = "/lib/i386-linux-gnu/libpcsclite.so.1";
    private static final String ubuntu64_path = "/lib/x86_64-linux-gnu/libpcsclite.so.1";
    private static final String freebsd_path = "/usr/local/lib/libpcsclite.so";
    private static final String fedora64_path = "/usr/lib64/libpcsclite.so.1";
    private static final String raspbian_path = "/usr/lib/arm-linux-gnueabihf/libpcsclite.so.1";

    // Only one active instance per JVM -PC/SC context is process-global
    private static final AtomicReference<TerminalManager> active = new AtomicReference<>();

    private final TerminalFactory factory;
    private final Object lock = new Object();
    // Per-thread SCardContext via jnasmartcardio
    private final ThreadLocal<CardTerminals> threadLocalTerminals = ThreadLocal.withInitial(() -> null);

    // Per-reader executor management
    private final ConcurrentHashMap<String, ReaderExecutor> executors = new ConcurrentHashMap<>();
    private volatile List<PCSCReader> currentReaders = List.of();
    private volatile Thread monitorThread;

    // Monitor state notification
    private final CountDownLatch initialScan = new CountDownLatch(1);
    private final ReentrantLock readersLock = new ReentrantLock();
    private final Condition readersUpdated = readersLock.newCondition();
    private record OnCardReg(Predicate<PCSCReader> matcher, BiConsumer<PCSCReader, CardTerminal> action, CardWatch watch) {
    }

    // Several live registrations may coexist, each closed via its own CardWatch. Their matchers
    // must not overlap: a reader is served by at most one registration.
    private final Set<OnCardReg> onCardRegs = ConcurrentHashMap.newKeySet();

    // Call from a single thread (typically main). Not safe under contention.
    public static TerminalManager getDefault() {
        var current = active.get();
        if (current != null) {
            return current;
        }
        return new TerminalManager(getTerminalFactory());
    }

    public TerminalManager(TerminalFactory factory) {
        if (!active.compareAndSet(null, this)) {
            throw new IllegalStateException("TerminalManager already active; close() existing instance first");
        }
        this.factory = factory;
    }

    public static TerminalManager replayManager(InputStream dump) {
        var terminals = new SynthesizedCardTerminals();
        terminals.addTerminal(SynthesizedCardTerminal.replay(dump));
        return new TerminalManager(terminals.toFactory());
    }

    public static TerminalManager managerOf(SynthesizedCardTerminal... terminals) {
        var sct = new SynthesizedCardTerminals();
        for (var t : terminals) {
            sct.addTerminal(t);
        }
        return new TerminalManager(sct.toFactory());
    }

    public CardTerminals terminals() {
        return terminals(false);
    }

    public CardTerminals terminals(boolean fresh) {
        var terms = threadLocalTerminals.get();
        // Explicitly release the old context if using jnasmartcardio
        if (fresh && terms instanceof Smartcardio.JnaCardTerminals jnaTerms) {
            try {
                jnaTerms.close();
            } catch (Smartcardio.JnaPCSCException e) {
                logger.warn("Could not release context: {}", SCard.getExceptionMessage(e), e);
            }
        }
        if (terms == null || fresh) {
            terms = factory.terminals();
            threadLocalTerminals.set(terms);
        }
        return terms;
    }

    public TerminalFactory factory() {
        return factory;
    }

    // Provider detection -used by ReaderSelectorImpl to resolve connect strings
    boolean isJna() {
        return factory.getProvider() instanceof jnasmartcardio.Smartcardio;
    }

    // Makes sure the associated context would be thread-local for jnasmartcardio and Linux
    public CardTerminal terminal(String name) {
        return terminals().getTerminal(name);
    }

    public static boolean isEnabled(String feature, boolean def) {
        return Boolean.parseBoolean(System.getProperty(feature, System.getenv().getOrDefault("_" + feature.toUpperCase(Locale.ROOT).replace(".", "_"), Boolean.toString(def))));
    }

    // SunPCSC needs to have the path to the loadable library to work, for whatever reasons.
    public static String detectLibraryPath() {
        // Would be nice to use Files.exists instead.
        final String os = System.getProperty("os.name");
        // Set necessary parameters for seamless PC/SC access.
        // http://ludovicrousseau.blogspot.com.es/2013/03/oracle-javaxsmartcardio-failures.html
        if ("Linux".equalsIgnoreCase(os)) {
            // Only try loading 64b paths if JVM can use them.
            if (System.getProperty("os.arch").contains("64")) {
                if (new File(debian64_path).exists()) {
                    return debian64_path;
                } else if (new File(fedora64_path).exists()) {
                    return fedora64_path;
                } else if (new File(ubuntu64_path).exists()) {
                    return ubuntu64_path;
                }
            } else if (new File(ubuntu_path).exists()) {
                return ubuntu_path;
            } else if (new File(ubuntu32_path).exists()) {
                return ubuntu32_path;
            } else if (new File(raspbian_path).exists()) {
                return raspbian_path;
            } else {
                // dlopen() works properly on Debian OpenJDK 7
                logger.info("Hint: pcsc-lite is probably missing");
            }
        } else if ("FreeBSD".equalsIgnoreCase(os)) {
            if (new File(freebsd_path).exists()) {
                return freebsd_path;
            } else {
                System.err.println("Hint: pcsc-lite is missing. pkg install devel/libccid");
            }
        } else if ("Mac OS X".equalsIgnoreCase(os)) {
            // Big Sur+: framework is in dyld shared cache, not on disk. Check parent dir like JDK does.
            if (new File("/System/Library/Frameworks/PCSC.framework/Versions/Current").isDirectory()) {
                return "/System/Library/Frameworks/PCSC.framework/Versions/Current/PCSC";
            }
        }
        return null;
    }

    /**
     * Locates PC/SC shared library on the system and automagically sets system properties so that SunPCSC
     * could find the smart card service. Call this before acquiring your TerminalFactory.
     */
    public static void fixPlatformPaths() {
        var lib = Optional.ofNullable(detectLibraryPath());
        if (System.getProperty(LIB_PROP) == null && lib.isPresent()) {
            System.setProperty(LIB_PROP, lib.get());
        }
    }

    // Detect JDK's dummy NoneProvider (returned by getDefault() when PC/SC is unavailable)
    public static boolean isNoneProvider(TerminalFactory factory) {
        return "javax.smartcardio.TerminalFactory.NoneCardTerminals"
                .equals(factory.terminals().getClass().getCanonicalName());
    }

    public static TerminalFactory getTerminalFactory() {
        // Prefer JNA - handles thread-local contexts and recovers from service restarts
        try {
            return TerminalFactory.getInstance("PC/SC", null, new Smartcardio());
        } catch (NoSuchAlgorithmException e) {
            logger.warn("jnasmartcardio unavailable ({}), falling back to SunPCSC", e.getMessage());
        }
        // SunPCSC fallback - fix library path BEFORE first use (PlatformPCSC.initException is static final)
        fixPlatformPaths();
        try {
            return TerminalFactory.getInstance("PC/SC", null);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "PC/SC is not available. Install pcsc-lite (Linux/FreeBSD) or check smart card service.", e);
        }
    }

    // Returns version from jar manifest (set by maven-jar-plugin)
    public static String getVersion() {
        return Optional.ofNullable(TerminalManager.class.getPackage().getImplementationVersion())
                .orElse("development");
    }

    public static List<PCSCReader> listPCSC(List<CardTerminal> terminals, OutputStream logStream, boolean probePinpad) throws CardException {
        return listPCSC(terminals, logStream, probePinpad, false);
    }

    // Fetch what is a combination of CardTerminal + Card data and handle all the weird errors of PC/SC
    public static List<PCSCReader> listPCSC(List<CardTerminal> terminals, OutputStream logStream, boolean probePinpad, boolean reportMute) throws CardException {
        var result = new ArrayList<PCSCReader>();
        for (CardTerminal t : terminals) {
            if (logStream != null) {
                t = LoggingCardTerminal.getInstance(t, logStream);
            }
            try {
                final var name = t.getName();
                var present = t.isCardPresent();
                var exclusive = false;
                var mute = false;
                String vmd = null;
                byte[] atr = null;
                if (present) {
                    Card c = null;
                    // Try to connect in shared mode, also detects EXCLUSIVE
                    try {
                        c = t.connect("*");
                        // If successful, we get the protocol and ATR
                        atr = c.getATR().getBytes();
                        if (probePinpad) {
                            vmd = PinPadTerminal.getVMD(t, c);
                        }
                    } catch (CardException e) {
                        String err = SCard.getExceptionMessage(e);
                        if (SCard.SCARD_W_UNPOWERED_CARD.equals(err)) {
                            logger.warn("Unpowered card. Contact card inserted wrong way or card mute?");
                            if (reportMute) {
                                mute = true;
                            } else {
                                present = false;
                            }
                        } else if (SCard.SCARD_E_NO_SMARTCARD.equals(err) || SCard.SCARD_W_REMOVED_CARD.equals(err) || SCard.SCARD_E_READER_UNAVAILABLE.equals(err)) {
                            // Race: card/reader removed between list and connect
                            logger.debug("Card removed from {} during enumeration", name);
                            present = false;
                        } else if (SCard.SCARD_E_SHARING_VIOLATION.equals(err)) {
                            exclusive = true;
                            // macOS allows to connect to reader in DIRECT mode when device is in EXCLUSIVE
                            try {
                                c = t.connect("DIRECT");
                                atr = c.getATR().getBytes();
                                if (probePinpad) {
                                    vmd = PinPadTerminal.getVMD(t, c);
                                }
                            } catch (CardException e2) {
                                String err2 = SCard.getExceptionMessage(e2);
                                if (probePinpad) {
                                    if (SCard.SCARD_E_SHARING_VIOLATION.equals(err2)) {
                                        vmd = "???";
                                    } else {
                                        vmd = "EEE";
                                        logger.warn("Unexpected error: {}", err2, e2);
                                    }
                                }
                            }
                        } else {
                            if (probePinpad) {
                                vmd = "EEE";
                            }
                            logger.warn("Unexpected error: {}", err, e);
                        }
                    } finally {
                        if (c != null) {
                            try {
                                c.disconnect(false);
                            } catch (CardException ignored) {
                                // Probe cleanup - may fail if another thread holds exclusive
                            }
                        }
                    }
                } else {
                    // Not present
                    if (probePinpad) {
                        Card c = null;
                        // Try to connect in DIRECT mode
                        try {
                            c = t.connect("DIRECT");
                            vmd = PinPadTerminal.getVMD(t, c);
                        } catch (CardException e) {
                            vmd = "EEE";
                            String err = SCard.getExceptionMessage(e);
                            logger.debug("Could not connect to reader in direct mode: {}", err, e);
                        } finally {
                            if (c != null) {
                                try {
                                    c.disconnect(false);
                                } catch (CardException ignored) {
                                    // Probe cleanup - may fail if another thread holds exclusive
                                }
                            }
                        }
                    }
                }
                result.add(new PCSCReader(name, atr, present, exclusive, mute, vmd));
            } catch (CardException e) {
                String err = SCard.getExceptionMessage(e);
                logger.warn("Unexpected PC/SC error: {}", err, e);
            }
        }
        return result;
    }

    // Start monitoring reader changes in a daemon thread. Idempotent.
    void startMonitor() {
        synchronized (lock) {
            if (monitorThread != null) {
                return;
            }
            var monitor = new HandyTerminalsMonitor(this, this);
            monitorThread = new Thread(monitor, "PC/SC Monitor");
            monitorThread.setDaemon(true);
            monitorThread.start();
        }
    }

    // Get or create a per-reader executor
    public ReaderExecutor executor(String readerName) {
        return executors.computeIfAbsent(readerName, ReaderExecutor::new);
    }

    public boolean isMonitorRunning() {
        var t = monitorThread;
        return t != null && t.isAlive();
    }

    public List<PCSCReader> readers() {
        if (isMonitorRunning()) {
            return List.copyOf(currentReaders);
        }
        try {
            return listPCSC(terminals().list(CardTerminals.State.ALL), null, false);
        } catch (CardException e) {
            throw new BIBOException("Failed to list readers", e);
        }
    }

    public boolean awaitInitialScan(Duration timeout) throws InterruptedException {
        startMonitor();
        return initialScan.await(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public boolean awaitReaders(Predicate<List<PCSCReader>> condition, Duration timeout) throws InterruptedException {
        startMonitor();
        readersLock.lock();
        try {
            var nanos = timeout.toNanos();
            while (!condition.test(currentReaders)) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = readersUpdated.awaitNanos(nanos);
            }
            return true;
        } finally {
            readersLock.unlock();
        }
    }

    CardWatch registerOnCard(Predicate<PCSCReader> matcher, BiConsumer<PCSCReader, CardTerminal> action, boolean fresh) {
        boolean monitorWasRunning = isMonitorRunning();
        if (fresh) {
            if (monitorWasRunning) {
                // Monitor already started - wait for its initial scan so currentReaders is authoritative
                try {
                    awaitInitialScan(Duration.ofSeconds(10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted awaiting initial scan", e);
                }
            } else {
                // Normal path: direct PCSC scan to seed before monitor starts
                readersLock.lock();
                try {
                    currentReaders = listPCSC(terminals().list(CardTerminals.State.ALL), null, false);
                } catch (CardException e) {
                    throw new BIBOException("Failed to seed reader state", e);
                } finally {
                    readersLock.unlock();
                }
            }
        }
        var reg = new OnCardReg(matcher, action, new CardWatch(this));
        // A reader is served by at most one pass: reject a matcher overlapping an existing one.
        readersLock.lock();
        try {
            for (var reader : currentReaders) {
                if (!matcher.test(reader)) {
                    continue;
                }
                for (var existing : onCardRegs) {
                    if (existing.matcher().test(reader)) {
                        throw new IllegalStateException("Reader '" + reader.name() + "' is already served by another pass");
                    }
                }
            }
            onCardRegs.add(reg);
        } finally {
            readersLock.unlock();
        }
        startMonitor();
        // A late non-fresh registration misses the monitor's initial scan: serve present readers now.
        if (!fresh && monitorWasRunning) {
            for (var reader : currentReaders) {
                if (reader.present() && !reader.mute() && matcher.test(reader)) {
                    dispatch(reg, reader);
                }
            }
        }
        return reg.watch();
    }

    // Identity-guarded so a stale handle's close() only evicts its own registration.
    void unregisterOnCard(CardWatch watch) {
        onCardRegs.removeIf(r -> r.watch() == watch);
    }

    // Run a registration's action on the executor thread, skipping it if the pass closed meanwhile.
    private void dispatch(OnCardReg reg, PCSCReader reader) {
        executor(reader.name()).run(() -> {
            if (onCardRegs.contains(reg)) {
                try {
                    reg.action().accept(reader, terminal(reader.name()));
                } catch (RuntimeException e) {
                    // Nobody waits on the executor's future, so a throwing handler would vanish here.
                    logger.error("On-card handler for {} failed: {}", reader.name(), e.getMessage(), e);
                }
            }
        });
    }

    // Unblock every awaiting pass and drop all registrations: monitor error or manager close.
    private void releaseAllPasses() {
        onCardRegs.forEach(reg -> reg.watch().release());
        onCardRegs.clear();
    }

    @Override
    public void readerListChanged(List<PCSCReader> states) {
        List<PCSCReader> previous;
        readersLock.lock();
        try {
            previous = currentReaders;
            currentReaders = List.copyOf(states);
            readersUpdated.signalAll();
        } finally {
            readersLock.unlock();
        }
        initialScan.countDown();
        logger.debug("Reader list changed: {}", states);

        if (onCardRegs.isEmpty()) {
            return;
        }

        for (var reader : states) {
            if (!reader.present() || reader.mute()) {
                continue;
            }
            var wasPresent = previous.stream()
                    .filter(r -> r.name().equals(reader.name()))
                    .anyMatch(PCSCReader::present);
            if (wasPresent) {
                continue;
            }
            // Matchers are disjoint, so at most one registration serves this fresh tap.
            for (var reg : onCardRegs) {
                if (reg.matcher().test(reader)) {
                    dispatch(reg, reader);
                }
            }
        }
    }

    @Override
    public void readerListErrored(Throwable t) {
        logger.error("Reader monitor error: {}", t.getMessage(), t);
        releaseAllPasses();
    }

    @Override
    public void close() {
        releaseAllPasses();
        synchronized (lock) {
            if (monitorThread != null) {
                monitorThread.interrupt();
                try {
                    monitorThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                monitorThread = null;
            }
            executors.values().forEach(ReaderExecutor::shutdown);
            executors.clear();
            active.compareAndSet(this, null);
        }
    }

    public static boolean isMacOS() {
        return "mac os x".equalsIgnoreCase(System.getProperty("os.name"));
    }

    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
