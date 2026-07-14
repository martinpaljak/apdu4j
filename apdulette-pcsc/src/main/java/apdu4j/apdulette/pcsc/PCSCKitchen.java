// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette.pcsc;

import apdu4j.apdulette.Chef;
import apdu4j.apdulette.Dish;
import apdu4j.apdulette.KitchenManager;
import apdu4j.core.BIBOSA;
import apdu4j.core.CardInfo;
import apdu4j.pcsc.CardWatch;
import apdu4j.pcsc.ReaderSelector;
import apdu4j.pcsc.Readers;
import apdu4j.prefs.Preferences;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

// PC/SC card source. The status-change monitor and per-reader workers live in the underlying
// TerminalManager: a card in reader R connects and runs its handler on R's worker, so teppanyaki
// and pass share one monitor and one worker per stove. Each session's stack passes through the
// decorator (transport wrappers) before cooking, preserving the preferences sidecar.
public final class PCSCKitchen implements KitchenManager {
    private final ReaderSelector selector;
    private final Function<BIBOSA, BIBOSA> decorate;
    private final AtomicBoolean active = new AtomicBoolean(false);

    private PCSCKitchen(ReaderSelector selector, Function<BIBOSA, BIBOSA> decorate) {
        this.selector = selector;
        this.decorate = decorate;
    }

    public static KitchenManager open() {
        return open(Readers.select(), new Preferences(), Function.identity());
    }

    public static KitchenManager open(ReaderSelector selector) {
        return open(selector, new Preferences(), Function.identity());
    }

    public static KitchenManager open(ReaderSelector selector, Preferences prefs, Function<BIBOSA, BIBOSA> decorate) {
        return new PCSCKitchen(configure(selector, prefs), decorate);
    }

    // Fold the neutral session hints into the PC/SC selector: an explicitly set hint drives the connection,
    // an unset one leaves the selector's own configuration standing. The configuration also joins the
    // preferences spine, so a served Dish carries the hints alongside the facts the backend publishes back.
    private static ReaderSelector configure(ReaderSelector selector, Preferences prefs) {
        var s = selector.with(prefs);
        if (prefs.valueOf(CardInfo.PROTOCOL).isPresent()) {
            s = s.protocol(prefs.get(CardInfo.PROTOCOL));
        }
        if (prefs.valueOf(CardInfo.FRESH).isPresent()) {
            s = s.fresh(prefs.get(CardInfo.FRESH));
        }
        if (prefs.valueOf(CardInfo.EXCLUSIVE).isPresent() && prefs.get(CardInfo.EXCLUSIVE)) {
            s = s.exclusive();
        }
        return s;
    }

    @Override
    public <T> CompletableFuture<T> teppanyaki(BiFunction<Chef, Preferences, T> handler) {
        claim();
        var future = new CompletableFuture<T>();
        // One token owns the session: the first card takes it and cooks, later cards and the cleanup
        // below lose the race and leave the kitchen alone. Whoever holds the token releases, so a
        // cancellation mid-cook keeps the kitchen busy until the handler actually returns.
        var taken = new AtomicBoolean(false);
        // Serve the first card at any allocated stove, on that stove's worker, then stop watching.
        CardWatch watch = selector.onCard((reader, stack) -> {
            if (!taken.compareAndSet(false, true)) {
                return;
            }
            // Freed on the worker before the result is observable, so a caller that reads the future
            // sees the kitchen free for the next handler.
            try {
                T value = runHandler(decorate.apply(stack), handler);
                release();
                future.complete(value);
            } catch (Throwable t) {
                release();
                future.completeExceptionally(t);
            }
        });
        // Closing the watch also frees on cancellation before a card ever arrives; the token loses to
        // an in-flight handler, so the kitchen stays claimed until that handler returns.
        future.whenComplete((r, t) -> {
            watch.close();
            if (taken.compareAndSet(false, true)) {
                release();
            }
        });
        return future;
    }

    @Override
    public <T> AutoCloseable pass(BiFunction<Chef, Preferences, T> handler, BiConsumer<Dish<T>, Throwable> results) {
        claim();
        CardWatch watch = selector.onCard((reader, stack) -> {
            try {
                BIBOSA composed = decorate.apply(stack);
                T value = runHandler(composed, handler);
                results.accept(new Dish<>(value, composed.preferences()), null);
            } catch (Throwable t) {
                results.accept(null, t);
            }
        });
        return () -> {
            watch.close();
            release();
        };
    }

    // The stove threads and connect/disconnect live in the TerminalManager, which the caller owns.
    @Override
    public void close() {
        release();
    }

    // Mint a Chef over the composed stack (already decorated by the caller) and run the handler with its
    // session facts. onCard closes the underlying stack afterwards, applying the baked-in disconnect disposition.
    private <T> T runHandler(BIBOSA stack, BiFunction<Chef, Preferences, T> handler) {
        Chef chef = Chef.of(stack);
        return handler.apply(chef, stack.preferences());
    }

    private void claim() {
        if (!active.compareAndSet(false, true)) {
            throw new IllegalStateException("A handler is already active on this kitchen");
        }
    }

    private void release() {
        active.set(false);
    }
}
