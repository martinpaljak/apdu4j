// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette.pcsc;

import apdu4j.apdulette.Chef;
import apdu4j.apdulette.Cookbook;
import apdu4j.apdulette.Dish;
import apdu4j.apdulette.PreparationStep;
import apdu4j.apdulette.Recipe;
import apdu4j.core.BIBO;
import apdu4j.core.BIBOSA;
import apdu4j.core.CardInfo;
import apdu4j.core.CommandAPDU;
import apdu4j.core.HexUtils;
import apdu4j.core.MockBIBO;
import apdu4j.pcsc.Readers;
import apdu4j.pcsc.TerminalManager;
import apdu4j.pcsc.sim.SynthesizedCardTerminal;
import apdu4j.pcsc.sim.SynthesizedCardTerminals;
import apdu4j.prefs.Preferences;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public class PCSCKitchenTest {

    private static final CommandAPDU SELECT = new CommandAPDU(0x00, 0xA4, 0x04, 0x00);
    private static final CommandAPDU GET_DATA = new CommandAPDU(0x00, 0xCA, 0x9F, 0x7F);

    // A transport wrapper that counts each transceive, folded into the BIBOSA stack via then() -
    // exactly how a consumer folds its own wrappers in.
    private static Function<BIBOSA, BIBOSA> counting(AtomicInteger transceives) {
        Function<BIBO, BIBO> counter = inner -> cmd -> {
            transceives.incrementAndGet();
            return inner.transceive(cmd);
        };
        return s -> s.then(counter);
    }

    // teppanyaki mints one Chef per card, runs the handler in a single session (recipes share the
    // connection, no mid-session reset), decorates the transport, folds the session preferences into
    // the Chef baseline, and hands the connected facts to the handler.
    @Test
    void teppanyakiDecoratesAndFoldsPrefs() throws Exception {
        var select = HexUtils.bin2hex(SELECT.getBytes());
        var getData = HexUtils.bin2hex(GET_DATA.getBytes());
        var terminals = new SynthesizedCardTerminals();
        var terminal = new SynthesizedCardTerminal("Contact Reader"); // starts empty
        terminals.addTerminal(terminal);

        var connects = new AtomicInteger(0);
        var transceives = new AtomicInteger(0);
        try (var mgr = new TerminalManager(terminals.toFactory());
             var kitchen = PCSCKitchen.open(Readers.select(mgr), new Preferences(), counting(transceives))) {
            Recipe<String> readsProtocol = p -> new PreparationStep.Premade<>(
                    p.valueOf(CardInfo.NEGOTIATED_PROTOCOL).orElse("none"));
            var sws = new int[2];
            var protocol = new AtomicReference<String>();

            var future = kitchen.teppanyaki((chef, prefs) -> {
                // One session: GET DATA follows SELECT on the same connection.
                sws[0] = chef.cook(Cookbook.send(SELECT)).getSW();
                sws[1] = chef.cook(Cookbook.send(GET_DATA)).getSW();
                protocol.set(chef.cook(readsProtocol));            // Chef folds session prefs
                return prefs;
            });

            // Each connect mints one session BIBO answering SELECT then GET DATA on the same connection.
            terminal.presentFactory(proto -> {
                connects.incrementAndGet();
                return MockBIBO.with(select, "9000").then(getData, "9000");
            }, SynthesizedCardTerminal.defaultAtr());

            var prefs = future.get(5, TimeUnit.SECONDS);

            Assert.assertEquals(sws[0], 0x9000);
            Assert.assertEquals(sws[1], 0x9000, "GET DATA reaches the same connection, no reset within a session");
            Assert.assertEquals(protocol.get(), "T=1", "Chef folds the session protocol as recipe baseline");
            Assert.assertEquals(transceives.get(), 2, "the served session is decorated");
            Assert.assertEquals(connects.get(), 1, "one session, one connection");
            Assert.assertEquals(prefs.valueOf(CardInfo.READER_NAME).orElse(null), "Contact Reader");
            Assert.assertTrue(prefs.valueOf(CardInfo.ATR).isPresent());
            Assert.assertTrue(prefs.valueOf(CardInfo.NEGOTIATED_PROTOCOL).isPresent());
            Assert.assertEquals(prefs.valueOf(CardInfo.FRESH_TAP).orElse(null), Boolean.TRUE,
                    "a required fresh tap is reported as a genuine power-on");
            Assert.assertEquals(prefs.valueOf(CardInfo.EXCLUSIVE_HELD).orElse(null), Boolean.FALSE);
        }
    }

    // teppanyaki returns immediately without blocking the caller and completes once a card arrives.
    @Test
    void teppanyakiIsNonBlockingAndWaitsForCard() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        var terminal = new SynthesizedCardTerminal("Contact Reader"); // starts empty
        terminals.addTerminal(terminal);

        try (var mgr = new TerminalManager(terminals.toFactory());
             var kitchen = PCSCKitchen.open(Readers.select(mgr))) {
            CompletableFuture<String> future = kitchen.teppanyaki((chef, prefs) ->
                    prefs.valueOf(CardInfo.NEGOTIATED_PROTOCOL).orElse(null));

            Assert.assertFalse(future.isDone(), "teppanyaki must return before a card is present");

            terminal.present(MockBIBO.of("9000"));
            Assert.assertNotNull(future.get(5, TimeUnit.SECONDS), "future completes once a card arrives");
        }
    }

    // pass serves every tap, each as one session on the reader thread, and stops once the returned
    // handle is closed.
    @Test
    void passServesTapsUntilClosed() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        var terminal = new SynthesizedCardTerminal("Contact Reader"); // starts empty
        terminals.addTerminal(terminal);

        var select = HexUtils.bin2hex(SELECT.getBytes());
        var getData = HexUtils.bin2hex(GET_DATA.getBytes());
        try (var mgr = new TerminalManager(terminals.toFactory());
             var kitchen = PCSCKitchen.open(Readers.select(mgr))) {
            var connects = new AtomicInteger(0);
            var dishes = new CopyOnWriteArrayList<Dish<Integer>>();
            var errors = new CopyOnWriteArrayList<Throwable>();
            var served = new CountDownLatch(1);

            BiConsumer<Dish<Integer>, Throwable> sink = (dish, err) -> {
                if (err != null) {
                    errors.add(err);
                } else {
                    dishes.add(dish);
                    served.countDown();
                }
            };
            var subscription = kitchen.pass((chef, prefs) -> {
                chef.cook(Cookbook.send(SELECT));                 // same session ...
                return chef.cook(Cookbook.send(GET_DATA)).getSW();  // ... GET DATA on the same connection
            }, sink);
            Assert.assertTrue(mgr.awaitInitialScan(Duration.ofSeconds(5)));

            terminal.presentFactory(proto -> {
                connects.incrementAndGet();
                return MockBIBO.with(select, "9000").then(getData, "9000");
            }, SynthesizedCardTerminal.defaultAtr());
            Assert.assertTrue(served.await(5, TimeUnit.SECONDS), "a tap must produce a dish");

            Assert.assertEquals(dishes.get(0).value().intValue(), 0x9000);
            Assert.assertEquals(dishes.get(0).preferences().valueOf(CardInfo.READER_NAME).orElse(null), "Contact Reader");
            Assert.assertEquals(connects.get(), 1, "one tap is one session, recipes share the connection");
            Assert.assertEquals(errors.size(), 0);

            subscription.close();
            terminal.yank();
            Assert.assertTrue(mgr.awaitReaders(readers -> readers.stream().noneMatch(r -> r.present()),
                    Duration.ofSeconds(5)));
            terminal.present(MockBIBO.of("9000"));
            Thread.sleep(300);
            Assert.assertEquals(dishes.size(), 1, "a closed subscription serves no further taps");
        }
    }

    // Only one handler runs at a time: a second teppanyaki()/pass() while one is active is rejected, and
    // a new handler is accepted once the active one finishes.
    @Test
    void oneHandlerAtATime() throws Exception {
        var terminals = new SynthesizedCardTerminals();
        var terminal = new SynthesizedCardTerminal("Contact Reader"); // starts empty
        terminals.addTerminal(terminal);

        try (var mgr = new TerminalManager(terminals.toFactory());
             var kitchen = PCSCKitchen.open(Readers.select(mgr))) {
            BiFunction<Chef, Preferences, String> handler = (chef, prefs) ->
                    prefs.valueOf(CardInfo.READER_NAME).orElse(null);

            var pending = kitchen.teppanyaki(handler); // waits on the empty reader, stays active
            Assert.assertThrows(IllegalStateException.class, () -> kitchen.teppanyaki(handler));
            Assert.assertThrows(IllegalStateException.class,
                    () -> kitchen.pass(handler, (dish, err) -> {
                    }));

            terminal.present(MockBIBO.of("9000"));
            Assert.assertEquals(pending.get(5, TimeUnit.SECONDS), "Contact Reader");

            // The finished handler released the kitchen: a fresh tap is served by a new teppanyaki.
            terminal.yank();
            Assert.assertTrue(mgr.awaitReaders(readers -> readers.stream().noneMatch(r -> r.present()),
                    Duration.ofSeconds(5)));
            var second = kitchen.teppanyaki(handler);
            terminal.present(MockBIBO.of("9000"));
            Assert.assertEquals(second.get(5, TimeUnit.SECONDS), "Contact Reader");
        }
    }
}
