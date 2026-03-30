/*
 * Copyright (c) 2026-present Martin Paljak <martin@martinpaljak.net>
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

import apdu4j.core.APDUBIBO;
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
    <T> T run(Function<APDUBIBO, T> fn);

    // BIBOSA variant - transport + enriched Preferences (ATR, negotiated protocol, config)
    <T> T open(Function<BIBOSA, T> fn);

    void accept(Consumer<APDUBIBO> fn);

    // Managed sessions - wait for card
    <T> T whenReady(Function<APDUBIBO, T> fn);

    <T> T whenReady(Duration timeout, Function<APDUBIBO, T> fn);

    // Unmanaged - caller manages lifecycle
    APDUBIBO connect();

    APDUBIBO connectWhenReady();

    APDUBIBO connectWhenReady(Duration timeout);

    // Continuous per-tap dispatch (requires monitor)
    void onCard(BiConsumer<PCSCReader, APDUBIBO> fn);

    // Escape hatches (bypass executor, caller thread)
    CardTerminal terminal();

    Card card();
}
