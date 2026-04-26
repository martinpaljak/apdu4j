// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette.pcsc;

import apdu4j.apdulette.Dish;
import apdu4j.apdulette.KitchenManager;
import apdu4j.apdulette.Recipe;
import apdu4j.apdulette.SousChef;
import apdu4j.pcsc.ReaderSelector;
import apdu4j.pcsc.Readers;
import apdu4j.prefs.Preferences;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

// PC/SC implementation of KitchenManager.
// Bridges apdulette recipes with PC/SC reader management.
public final class PCSCKitchenManager implements KitchenManager {
    private final ReaderSelector selector;
    private final Preferences prefs;

    public PCSCKitchenManager(ReaderSelector selector, Preferences prefs) {
        this.selector = selector.with(prefs);
        this.prefs = prefs;
    }

    @Override
    public <T> T teppanyaki(Function<ChefSession, T> fn) {
        return selector.open(bibosa -> {
            var chef = new SousChef(bibosa);
            return fn.apply(new ChefSession(chef, bibosa));
        });
    }

    @Override
    public <T> void kitchenPass(Recipe<T> recipe,
                                Consumer<Dish<T>> consumer,
                                BiConsumer<Preferences, Exception> onError) {
        selector.onCard((reader, apdubibo) -> {
            var sessionPrefs = prefs.with(Readers.READER_NAME, reader.name());
            try {
                var chef = new SousChef(apdubibo);
                var dish = chef.serve(recipe, sessionPrefs);
                consumer.accept(dish);
            } catch (Exception e) {
                onError.accept(sessionPrefs, e);
            }
        });
    }

    @Override
    public void close() {
        // ReaderSelector doesn't own TerminalManager lifecycle
    }
}
