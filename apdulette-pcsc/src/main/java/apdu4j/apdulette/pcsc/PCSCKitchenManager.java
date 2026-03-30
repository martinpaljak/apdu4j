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
