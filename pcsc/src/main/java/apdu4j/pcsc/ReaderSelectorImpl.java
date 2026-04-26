// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import apdu4j.core.*;
import apdu4j.pcsc.sim.SynthesizedTerminalsProvider;
import apdu4j.pcsc.terminals.LoggingCardTerminal;
import apdu4j.prefs.Preference;
import apdu4j.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

record ReaderSelectorImpl(
        TerminalManager mgr,
        SelectionCriteria selection,
        Preferences config,
        OutputStream logStream,
        OutputStream dumpStream
) implements ReaderSelector {
    private static final Logger logger = LoggerFactory.getLogger(ReaderSelectorImpl.class);

    record SelectionCriteria(String hint, List<String> ignoreFragments, Predicate<PCSCReader> filter) {
        static final SelectionCriteria DEFAULT = new SelectionCriteria(null, List.of(), r -> true);

        SelectionCriteria {
            ignoreFragments = List.copyOf(ignoreFragments);
        }
    }

    // Convenience constructor for factory methods
    ReaderSelectorImpl(TerminalManager mgr, SelectionCriteria selection) {
        this(mgr, selection, new Preferences(), null, null);
    }

    // --- Selection (return ReaderSelector) ---

    @Override
    public ReaderSelector select(String hint) {
        if (hint == null) {
            throw new IllegalArgumentException("hint must not be null");
        }
        return new ReaderSelectorImpl(mgr, new SelectionCriteria(hint, selection.ignoreFragments(), selection.filter()), config, logStream, dumpStream);
    }

    @Override
    public ReaderSelector ignore(String... fragments) {
        var merged = new ArrayList<>(selection.ignoreFragments());
        merged.addAll(List.of(fragments));
        return new ReaderSelectorImpl(mgr, new SelectionCriteria(selection.hint(), merged, selection.filter()), config, logStream, dumpStream);
    }

    @Override
    public ReaderSelector filter(Predicate<PCSCReader> predicate) {
        return new ReaderSelectorImpl(mgr, new SelectionCriteria(selection.hint(), selection.ignoreFragments(), selection.filter().and(predicate)), config, logStream, dumpStream);
    }

    @Override
    public ReaderSelector withCard() {
        return filter(PCSCReader::present);
    }

    // --- Configuration via Preferences ---

    @Override
    public ReaderSelector with(Preferences prefs) {
        return new ReaderSelectorImpl(mgr, selection, config.merge(prefs), logStream, dumpStream);
    }

    @Override
    public <V> ReaderSelector with(Preference<V> key, V value) {
        return new ReaderSelectorImpl(mgr, selection, config.with(key, value), logStream, dumpStream);
    }

    // --- Convenience sugar ---

    @Override
    public ReaderSelector protocol(String protocol) {
        return with(Readers.PROTOCOL, protocol);
    }

    @Override
    public ReaderSelector exclusive() {
        return with(Readers.EXCLUSIVE, true);
    }

    @Override
    public ReaderSelector reset(boolean reset) {
        return with(Readers.RESET, reset);
    }

    @Override
    public ReaderSelector transactions(boolean enable) {
        return with(Readers.TRANSACTIONS, enable);
    }

    @Override
    public ReaderSelector fresh(boolean requireFreshTap) {
        return with(Readers.FRESH_TAP, requireFreshTap);
    }

    // --- Runtime objects ---

    @Override
    public ReaderSelector log(OutputStream out) {
        return new ReaderSelectorImpl(mgr, selection, config, out, dumpStream);
    }

    @Override
    public ReaderSelector dump(OutputStream out) {
        return new ReaderSelectorImpl(mgr, selection, config, logStream, out);
    }

    // --- List ---

    @Override
    public List<PCSCReader> list() {
        return mgr.readers();
    }

    // --- Managed sessions ---

    @Override
    public <T> T run(Function<APDUBIBO, T> fn) {
        return open(bibosa -> fn.apply(new APDUBIBO(bibosa)));
    }

    @Override
    public <T> T open(Function<BIBOSA, T> fn) {
        var name = resolveReaderName();
        return withCardTerminal(name, ct -> connectAndRun(wrapLog(ct), fn));
    }

    @Override
    public void accept(Consumer<APDUBIBO> fn) {
        run(bibo -> {
            fn.accept(bibo);
            return null;
        });
    }

    @Override
    public <T> T whenReady(Function<APDUBIBO, T> fn) {
        var name = resolveReaderName();
        return withCardTerminal(name, ct -> {
            var wct = wrapLog(ct);
            waitForCard(wct, Duration.ZERO);
            return connectAndRun(wct, bibosa -> fn.apply(new APDUBIBO(bibosa)));
        });
    }

    @Override
    public <T> T whenReady(Duration timeout, Function<APDUBIBO, T> fn) {
        if (timeout.isZero()) {
            return whenReady(fn);
        }
        var name = resolveReaderName();
        return submitAndGet(name, () -> {
            var wct = wrapLog(mgr.terminal(name));
            waitForCard(wct, timeout);
            return connectAndRun(wct, bibosa -> fn.apply(new APDUBIBO(bibosa)));
        });
    }

    // --- Unmanaged ---

    @Override
    public APDUBIBO connect() {
        var name = resolveReaderName();
        var raw = withCardTerminal(name, ct -> connectRaw(wrapLog(ct)));
        return new APDUBIBO(maybeMarshal(name, raw));
    }

    @Override
    public APDUBIBO connectWhenReady() {
        var name = resolveReaderName();
        var raw = withCardTerminal(name, ct -> {
            var wct = wrapLog(ct);
            waitForCard(wct, Duration.ZERO);
            return connectRaw(wct);
        });
        return new APDUBIBO(maybeMarshal(name, raw));
    }

    @Override
    public APDUBIBO connectWhenReady(Duration timeout) {
        if (timeout.isZero()) {
            return connectWhenReady();
        }
        var name = resolveReaderName();
        var raw = submitAndGet(name, () -> {
            var wct = wrapLog(mgr.terminal(name));
            waitForCard(wct, timeout);
            return connectRaw(wct);
        });
        return new APDUBIBO(ReaderExecutor.wrap(mgr.executor(name), raw));
    }

    // --- Continuous per-tap dispatch ---

    @Override
    public void onCard(BiConsumer<PCSCReader, APDUBIBO> fn) {
        Predicate<PCSCReader> matcher = selection.filter();
        if (selection.hint() != null && !selection.hint().isBlank()) {
            var h = selection.hint().toLowerCase();
            matcher = matcher.and(r -> r.name().toLowerCase().contains(h));
        }
        if (!selection.ignoreFragments().isEmpty()) {
            var fragments = selection.ignoreFragments();
            matcher = matcher.and(r -> !Readers.isIgnored(fragments, r.name()));
        }
        mgr.registerOnCard(matcher, (reader, ct) -> {
            try {
                var wct = wrapLog(ct);
                applyTransparentMode();
                var card = wct.connect(resolveConnectProtocol());
                var bibosa = wrapBIBO(card, ct.getName());
                try {
                    fn.accept(reader, new APDUBIBO(bibosa));
                } finally {
                    bibosa.close();
                }
            } catch (CardException e) {
                var err = SCard.getExceptionMessage(e);
                if (SCard.SCARD_E_READER_UNAVAILABLE.equals(err)) {
                    logger.debug("Reader {} removed during onCard", reader.name());
                } else {
                    logger.warn("onCard failed for {}: {}", reader.name(), err);
                }
            }
        }, config.get(Readers.FRESH_TAP));
    }

    // --- Escape hatches ---

    @Override
    public CardTerminal terminal() {
        var name = resolveReaderName();
        return wrapLog(mgr.terminal(name));
    }

    @Override
    public Card card() {
        try {
            applyTransparentMode();
            return terminal().connect(resolveConnectProtocol());
        } catch (CardException e) {
            throw new BIBOException("Failed to connect", e);
        }
    }

    // --- Dual-mode dispatch ---

    private <T> T withCardTerminal(String name, Function<CardTerminal, T> fn) {
        if (mgr.isMonitorRunning()) {
            try {
                return mgr.executor(name).submit(() -> fn.apply(mgr.terminal(name))).join();
            } catch (CompletionException e) {
                throw unwrap(e);
            }
        }
        return fn.apply(mgr.terminal(name));
    }

    private BIBO maybeMarshal(String name, BIBO raw) {
        if (mgr.isMonitorRunning()) {
            return ReaderExecutor.wrap(mgr.executor(name), raw);
        }
        return raw;
    }

    // --- Extracted helpers ---

    private <T> T connectAndRun(CardTerminal ct, Function<BIBOSA, T> fn) {
        // Explicit TRANSACTIONS overrides; otherwise derive from EXCLUSIVE
        boolean useTransactions = config.valueOf(Readers.TRANSACTIONS)
                .orElse(!config.get(Readers.EXCLUSIVE));
        try {
            applyTransparentMode();
            var card = ct.connect(resolveConnectProtocol());
            try {
                if (useTransactions) {
                    card.beginExclusive();
                }
                var bibosa = wrapBIBO(card, ct.getName());
                try {
                    return fn.apply(bibosa);
                } finally {
                    try {
                        if (useTransactions) {
                            card.endExclusive();
                        }
                    } catch (CardException ignored) {
                        // endExclusive() cleanup - card may already be disconnected
                    }
                    bibosa.close();
                }
            } catch (CardException e) {
                // beginExclusive() failed - disconnect the card before propagating
                try {
                    card.disconnect(false);
                } catch (CardException ignored) {
                }
                throw e;
            }
        } catch (CardException e) {
            throw new BIBOException("Failed to connect", e);
        }
    }

    // Duration.ZERO = wait indefinitely (maps to waitForCardPresent(0))
    private void waitForCard(CardTerminal ct, Duration timeout) {
        try {
            if (config.get(Readers.FRESH_TAP) && ct.isCardPresent()) {
                logger.info("Card already present, waiting for removal before accepting new tap");
                if (!ct.waitForCardAbsent(timeout.toMillis())) {
                    throw new BIBOException("Timeout waiting for card removal");
                }
            }
            if (!ct.waitForCardPresent(timeout.toMillis())) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new RuntimeException(new InterruptedException("waitForCard"));
                }
                throw new BIBOException("Timeout waiting for card");
            }
        } catch (CardException e) {
            throw new BIBOException("Failed waiting for card", e);
        }
    }

    private BIBO connectRaw(CardTerminal ct) {
        try {
            applyTransparentMode();
            return wrapBIBO(ct.connect(resolveConnectProtocol()), ct.getName());
        } catch (CardException e) {
            throw new BIBOException("Failed to connect", e);
        }
    }

    // --- Internal helpers ---

    private String resolveReaderName() {
        return Readers.dwim(mgr.readers(), selection.hint(), selection.ignoreFragments(), selection.filter());
    }

    // Resolve the protocol string for the actual PC/SC backend.
    // EXCLUSIVE; prefix is jnasmartcardio-specific - SunPCSC rejects it.
    // T=CL is a synthetic contactless protocol (e.g. JCardEngine) - real PC/SC doesn't recognize it.
    private String resolveConnectProtocol() {
        var p = config.get(Readers.PROTOCOL);
        var synthesized = mgr.factory().getProvider() instanceof SynthesizedTerminalsProvider;

        // T=CL -> "*" for real PC/SC; pass through for synthesized (simulator needs it)
        if ("T=CL".equals(p) && !synthesized) {
            p = "*";
        }

        if (config.get(Readers.EXCLUSIVE)) {
            if (mgr.isJna()) {
                return "EXCLUSIVE;" + p;
            }
            if (!synthesized) {
                throw new UnsupportedOperationException("Exclusive connect requires jnasmartcardio");
            }
        }
        return p;
    }

    private void applyTransparentMode() {
        if (config.get(Readers.TRANSPARENT)) {
            System.setProperty("sun.security.smartcardio.t0GetResponse", "false");
            System.setProperty("sun.security.smartcardio.t1GetResponse", "false");
            System.setProperty("jnasmartcardio.transparent", "true");
        }
    }

    private CardTerminal wrapLog(CardTerminal t) {
        return logStream != null ? LoggingCardTerminal.getInstance(t, logStream) : t;
    }

    // Wraps javax.smartcardio.Card into BIBOSA with session facts as readonly preferences
    private BIBOSA wrapBIBO(Card card, String readerName) {
        var disconnect = config.get(Readers.RESET) ? SCard.Disconnect.RESET : SCard.Disconnect.LEAVE;
        BIBO bibo = CardBIBO.wrap(card, disconnect);
        if (dumpStream != null) {
            var ps = new PrintStream(dumpStream, true, StandardCharsets.UTF_8);
            ps.println("# ATR: " + HexUtils.bin2hex(card.getATR().getBytes()));
            ps.println("# PROTOCOL: " + card.getProtocol());
            ps.println("#");
            bibo = DumpingBIBO.wrap(bibo, dumpStream);
        }
        // Enrich config with session facts (readonly - can't be overwritten downstream)
        var sessionPrefs = config
                .with(Readers.READER_NAME, readerName)
                .with(Readers.ATR, HexBytes.b(card.getATR().getBytes()))
                .with(Readers.NEGOTIATED_PROTOCOL, card.getProtocol());
        return new BIBOSA(bibo, sessionPrefs);
    }

    // Submit to per-reader executor and block until done. Card-wait timeout
    // is enforced inside the callable; operation after card arrives runs unbounded.
    private <T> T submitAndGet(String name, Callable<T> task) {
        try {
            return mgr.executor(name).submit(task).get();
        } catch (ExecutionException e) {
            throw unwrap(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BIBOException("Interrupted waiting for card", e);
        }
    }

    private static BIBOException unwrap(Exception e) {
        var cause = e.getCause();
        if (cause instanceof BIBOException b) {
            return b;
        }
        return new BIBOException(cause != null ? cause.getMessage() : e.getMessage(), cause != null ? cause : e);
    }
}
