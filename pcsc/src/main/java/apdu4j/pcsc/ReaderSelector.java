// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import apdu4j.core.BIBO;
import apdu4j.core.BIBOSA;
import apdu4j.prefs.Preference;
import apdu4j.prefs.Preferences;

import javax.smartcardio.Card;
import javax.smartcardio.CardTerminal;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public sealed interface ReaderSelector permits ReaderSelectorImpl {
    // Selection (composable, return ReaderSelector)
    ReaderSelector select(String hint);

    ReaderSelector ignore(String... fragments);

    ReaderSelector filter(Predicate<PCSCReader> predicate);

    ReaderSelector withCard();

    // Configuration via Preferences
    ReaderSelector with(Preferences prefs);

    <V> ReaderSelector with(Preference<V> key, V value);

    // Convenience sugar for common preferences
    ReaderSelector protocol(String protocol);

    ReaderSelector exclusive();

    ReaderSelector reset(boolean reset);

    ReaderSelector transactions(boolean enable);

    ReaderSelector fresh(boolean requireFreshTap);

    // Runtime objects (not preference-able)
    ReaderSelector log(OutputStream out);

    ReaderSelector dump(OutputStream out);

    // List available readers
    List<PCSCReader> list();

    // Managed sessions - card must be present
    <T> T run(Function<BIBO, T> fn);

    // BIBOSA variant - transport + enriched Preferences (ATR, negotiated protocol, config)
    <T> T open(Function<BIBOSA, T> fn);

    void accept(Consumer<BIBO> fn);

    // Managed sessions - wait for card
    <T> T whenReady(Function<BIBO, T> fn);

    <T> T whenReady(Duration timeout, Function<BIBO, T> fn);

    // Unmanaged - caller manages lifecycle
    BIBO connect();

    BIBO connectWhenReady();

    BIBO connectWhenReady(Duration timeout);

    // Continuous per-tap dispatch (requires monitor)
    void onCard(BiConsumer<PCSCReader, BIBO> fn);

    // Escape hatches (bypass executor, caller thread)
    CardTerminal terminal();

    Card card();
}
