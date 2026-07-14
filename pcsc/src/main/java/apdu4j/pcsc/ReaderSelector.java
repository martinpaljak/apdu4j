// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.pcsc;

import apdu4j.core.BIBOSA;
import apdu4j.prefs.Preference;
import apdu4j.prefs.Preferences;

import javax.smartcardio.Card;
import javax.smartcardio.CardTerminal;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public sealed interface ReaderSelector permits ReaderSelectorImpl {
    // Selection (composable, return ReaderSelector)
    ReaderSelector select(String hint);

    ReaderSelector ignore(String... fragments);

    ReaderSelector filter(Predicate<PCSCReader> predicate);

    // True if this and other could serve the same reader: either one matches any reader, or both
    // are bound to the same reader via select(). Shared ignore/filter scoping is not compared.
    boolean overlaps(ReaderSelector other);

    ReaderSelector withCard();

    // Configuration via Preferences
    ReaderSelector with(Preferences prefs);

    <V> ReaderSelector with(Preference<V> key, V value);

    // Convenience sugar for common preferences
    ReaderSelector protocol(String protocol);

    ReaderSelector exclusive();

    ReaderSelector reset(boolean reset);

    ReaderSelector disconnect(SCard.Disconnect how);

    ReaderSelector transactions(boolean enable);

    ReaderSelector fresh(boolean requireFreshTap);

    // Runtime objects (not preference-able)
    ReaderSelector log(OutputStream out);

    ReaderSelector dump(OutputStream out);

    // Which card transition a wait is blocking on.
    enum Wait { REMOVAL, INSERTION }

    // Called when a wait is about to block, with the phase and resolved reader name. The returned
    // Runnable is run once the wait ends, however it ends; a GUI returns its dialog dismissal.
    ReaderSelector onWait(BiFunction<Wait, String, Runnable> notifier);

    // List available readers
    List<PCSCReader> list();

    // Managed session, card must be present. fn receives a BIBOSA (BIBO plus Preferences sidecar).
    <T> T run(Function<? super BIBOSA, ? extends T> fn);

    /**
     * @deprecated use {@link #run(Function)}.
     */
    @Deprecated
    default <T> T open(Function<? super BIBOSA, ? extends T> fn) {
        return run(fn);
    }

    void accept(Consumer<? super BIBOSA> fn);

    // Managed sessions - wait for card
    <T> T whenReady(Function<? super BIBOSA, ? extends T> fn);

    <T> T whenReady(Duration timeout, Function<? super BIBOSA, ? extends T> fn);

    // Caller manages lifecycle; the sidecar survives the per-reader executor proxy under a monitor.
    BIBOSA connect();

    BIBOSA connectWhenReady();

    BIBOSA connectWhenReady(Duration timeout);

    // Continuous per-tap dispatch (requires monitor); close the returned watch to stop.
    CardWatch onCard(BiConsumer<PCSCReader, ? super BIBOSA> fn);

    // Escape hatches (bypass executor, caller thread)
    CardTerminal terminal();

    Card card();
}
