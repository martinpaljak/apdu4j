// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import apdu4j.core.HexBytes;
import apdu4j.prefs.Preference;
import apdu4j.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class Readers {
    private static final Logger logger = LoggerFactory.getLogger(Readers.class);

    // Session preferences - configure card connection
    public static final Preference.Default<String> PROTOCOL =
            Preference.of("reader.protocol", String.class, "*", false,
                    p -> Set.of("T=0", "T=1", "T=CL", "*", "DIRECT").contains(p));
    public static final Preference.Default<Boolean> EXCLUSIVE =
            Preference.of("reader.exclusive", Boolean.class, false, false);
    public static final Preference.Default<Boolean> RESET =
            Preference.of("reader.reset", Boolean.class, true, false);
    // Explicitly set overrides the default (derive from EXCLUSIVE)
    public static final Preference.Default<Boolean> TRANSACTIONS =
            Preference.of("reader.transactions", Boolean.class, true, false);
    public static final Preference.Default<Boolean> FRESH_TAP =
            Preference.of("reader.fresh", Boolean.class, true, false);
    public static final Preference.Default<Boolean> TRANSPARENT =
            Preference.of("reader.transparent", Boolean.class, false, false);

    // Session facts - set at connect time, readonly
    public static final Preference.Parameter<String> READER_NAME =
            Preference.parameter("reader.name", String.class, true);
    public static final Preference.Parameter<HexBytes> ATR =
            Preference.parameter("card.atr", HexBytes.class, true);
    public static final Preference.Parameter<String> NEGOTIATED_PROTOCOL =
            Preference.parameter("card.protocol", String.class, true);

    private Readers() {
    }

    // Parse semicolon-separated ignore string into validated fragments (>= 3 chars).
    // This is the single place where raw env/CLI ignore strings are split and validated.
    public static List<String> parseIgnoreHints(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        var fragments = Arrays.stream(raw.split(";")).map(String::strip).filter(f -> !f.isEmpty()).toList();
        var short_ = fragments.stream().filter(f -> f.length() < 3).toList();
        if (!short_.isEmpty()) {
            logger.warn("Ignoring too short hint(s) (< 3 chars): {}", short_);
        }
        return fragments.stream().filter(f -> f.length() >= 3).toList();
    }

    // Annotate a reader snapshot with preferred/ignored flags.
    // Used for display (FancyChooser, reader list) and as input to dwim() selection.
    public static List<PCSCReader> dwimify(List<PCSCReader> readers, String hint, List<String> ignoreFragments) {
        if (readers.isEmpty()) {
            return readers;
        }
        var preferred = resolveHint(hint, readers);
        return readers.stream().map(r -> {
            if (r.name().equals(preferred)) {
                return r.withPreferred(true);
            }
            if (isIgnored(ignoreFragments, r.name())) {
                return r.withIgnored(true);
            }
            return r;
        }).toList();
    }

    // Full DWIM selection: annotate -> prefer -> auto-pick -> fail
    public static String dwim(List<PCSCReader> readers, String hint, List<String> ignoreFragments, Predicate<PCSCReader> filter) {
        return dwim(readers, hint, ignoreFragments, filter, null);
    }

    static String dwim(List<PCSCReader> readers, String hint, List<String> ignoreFragments, Predicate<PCSCReader> filter, Consumer<String> messages) {
        var hint0 = hint != null && hint.isBlank() ? null : hint;

        // Phase 1: filter and annotate
        var annotated = dwimify(readers.stream().filter(filter).toList(), hint0, ignoreFragments);

        // Phase 2: hint-matched reader wins (even without card)
        var preferred = annotated.stream().filter(PCSCReader::preferred).findFirst();
        if (preferred.isPresent()) {
            return preferred.get().name();
        }

        // Phase 3: auto-pick from eligible (non-ignored) readers
        var eligible = annotated.stream().filter(r -> !r.ignored()).toList();
        String picked;
        if (eligible.size() == 1) {
            // Single eligible reader -> pick it (even without card)
            picked = eligible.get(0).name();
        } else {
            // Multiple eligible -> need exactly one with card present and not exclusive
            var withCard = eligible.stream().filter(r -> r.present() && !r.exclusive()).limit(2).toList();
            picked = withCard.size() == 1 ? withCard.get(0).name() : null;
        }

        if (picked != null) {
            // Fallback message: hint was given but didn't resolve in Phase 2
            if (hint0 != null) {
                var msg = "'%s' did not match any reader, using %s".formatted(hint0, picked);
                if (messages != null) {
                    messages.accept(msg);
                } else {
                    logger.info(msg);
                }
            }
            return picked;
        }

        throw new NoMatchingReaderException(annotated.stream().map(PCSCReader::name).toList());
    }

    // Resolve a hint to an original reader name. Returns null if no match.
    private static String resolveHint(String hint, List<PCSCReader> readers) {
        if (hint == null || hint.isBlank()) {
            return null;
        }
        // Index hint: "1", "2", ... "99"
        if (hint.matches("\\d{1,2}")) {
            var index = Integer.parseInt(hint);
            if (index >= 1 && index <= readers.size()) {
                logger.debug("Chose {} by index {}", readers.get(index - 1).name(), index);
                return readers.get(index - 1).name();
            }
            logger.warn("Reader index out of bounds: {} vs {}", index, readers.size());
            return null;
        }
        // Fragment hint: must be >= 3 chars
        if (hint.length() < 3) {
            return null;
        }
        var aliases = ReaderAliases.getDefault().apply(readers.stream().map(PCSCReader::name).toList());
        var h = hint.toLowerCase();
        var matches = readers.stream()
                .filter(r -> aliases.extended(r.name()).toLowerCase().contains(h))
                .toList();
        return matches.size() == 1 ? matches.get(0).name() : null;
    }

    // Returns true if the reader name matches any ignore fragment
    public static boolean isIgnored(List<String> fragments, String name) {
        var lower = name.toLowerCase();
        return fragments.stream().anyMatch(f -> lower.contains(f.toLowerCase()));
    }

    // DWIM: auto-pick single reader, fail on ambiguity
    public static ReaderSelector select() {
        return select(TerminalManager.getDefault());
    }

    // Explicit hint (name fragment or 1-indexed number)
    public static ReaderSelector select(String hint) {
        return new ReaderSelectorImpl(TerminalManager.getDefault(), new ReaderSelectorImpl.SelectionCriteria(hint, List.of(), r -> true));
    }

    // Custom TerminalManager
    public static ReaderSelector select(TerminalManager mgr) {
        return new ReaderSelectorImpl(mgr, ReaderSelectorImpl.SelectionCriteria.DEFAULT);
    }

    // Custom TerminalManager + explicit hint
    public static ReaderSelector select(TerminalManager mgr, String hint) {
        return new ReaderSelectorImpl(mgr, new ReaderSelectorImpl.SelectionCriteria(hint, List.of(), r -> true));
    }

    // Load reader selection from a Preferences instance using consumer-defined keys.
    // Caller owns the namespace - apdu4j tool uses apdu4j.reader, other apps use their own.
    // Accepts either Default<String> or Parameter<String> via the sealed parent type.
    public static ReaderSelector fromPreferences(Preferences prefs,
                                                 Preference<String> hintKey,
                                                 Preference<String> ignoreKey) {
        return fromPreferences(TerminalManager.getDefault(), prefs, hintKey, ignoreKey);
    }

    public static ReaderSelector fromPreferences(TerminalManager mgr, Preferences prefs,
                                                 Preference<String> hintKey,
                                                 Preference<String> ignoreKey) {
        var hint = prefs.valueOf(hintKey).orElse("");
        var ignores = parseIgnoreHints(prefs.valueOf(ignoreKey).orElse(""));
        return new ReaderSelectorImpl(mgr,
                new ReaderSelectorImpl.SelectionCriteria(hint.isEmpty() ? null : hint, ignores, r -> true),
                prefs, null, null);
    }
}
