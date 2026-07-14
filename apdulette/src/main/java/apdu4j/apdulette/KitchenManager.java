// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette;

import apdu4j.prefs.Preferences;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

// Card source: owns all threads and connect/disconnect, runs one handler in pull or push mode.
// A handler is a BiFunction<Chef, Preferences, T>: the Chef is one session, the Preferences the
// session's negotiated facts. The same handler code serves both modes.
public interface KitchenManager extends AutoCloseable {

    // Pull: run the handler against the next card. Returns immediately without blocking the caller;
    // the future completes from the library's worker once a card arrives and the handler has run.
    <T> CompletableFuture<T> teppanyaki(BiFunction<Chef, Preferences, T> handler);

    // Push: run the handler against every card until the returned handle is closed. The sink gets
    // (dish, null) on success and (null, cause) on failure.
    <T> AutoCloseable pass(BiFunction<Chef, Preferences, T> handler, BiConsumer<Dish<T>, Throwable> results);

    // Only one handler runs per manager at a time: calling teppanyaki() or pass() while a handler is
    // active throws IllegalStateException.
    @Override
    void close();
}
