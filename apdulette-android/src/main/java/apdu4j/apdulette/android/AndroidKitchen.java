// SPDX-FileCopyrightText: 2026 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.apdulette.android;

import android.app.Activity;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TagLostException;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.util.Log;
import android.view.WindowManager;
import apdu4j.apdulette.Chef;
import apdu4j.apdulette.Dish;
import apdu4j.apdulette.KitchenManager;
import apdu4j.core.BIBOSA;
import apdu4j.core.CardInfo;
import apdu4j.core.HexBytes;
import apdu4j.prefs.Preferences;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

// A KitchenManager over one Android NFC reader, held by an Activity for its whole life. Reader mode is the
// card source: a tap sets the card in the field, and the manager mints one Chef per tap over that card. A
// Chef is one session; the several recipes one card needs (an M/Chip lists two applications and GPO binds
// the first) all run in that session, on the same tap.
//
// teppanyaki() is the pull path: it returns at once and completes on the reader-mode worker when a tap lands
// and the handler has run against the card. pass() is the push path: every tap runs the handler and delivers
// the dish. The card in the field feeds a waiting teppanyaki(); a registered pass() takes taps directly. Only
// one handler runs at a time.
//
// The session configuration prefs fix how every session runs (exclusivity, the freshness contract) before
// any card is touched; connecting a tag starts its facts from that configuration and enhances them with the card's
// contactless facts. The decorator applies the transport wrappers (GET RESPONSE, 6Cxx retry) the caller
// needs. There is no ATR over contactless, so CardInfo.ATR is left unset; the reader name (the phone), the
// negotiated protocol (T=CL), the UID, the ATS and the NFC tech list stand in.
//
// The Activity lifecycle surface (onResume/onPause/onNewIntent/setPrompt/available/enabled/keepAwake/close)
// is Android-specific and sits beside the platform-free KitchenManager methods, so the open() factory hands
// back the concrete type.
public final class AndroidKitchen implements KitchenManager {

    private static final String TAG = "AndroidKitchen";

    private final Activity activity;
    private final NfcAdapter nfc;
    private final Function<BIBOSA, BIBOSA> decorate;
    // Session configuration fixed at acquisition: the spine every session's Preferences grows from.
    private final Preferences prefs;
    // One reader-mode worker: teppanyaki() runs its wait-connect-handle cycle here, and a pass() tap is
    // dispatched here too, off the caller's and the NFC callback's threads.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "android-kitchen");
        t.setDaemon(true);
        return t;
    });
    // One handler per manager: a second teppanyaki()/pass() while one is active is rejected.
    private final AtomicBoolean active = new AtomicBoolean(false);
    // Held while a push handler runs, so a tap arriving mid-read is dropped rather than dispatched into it.
    private final AtomicBoolean busy = new AtomicBoolean(false);

    // The card in the field, set by the last accepted tap; teppanyaki() connects a fresh IsoDep from it. Null
    // when none. Identity distinguishes taps: a session that finds the field replaced does not clear the newcomer.
    private volatile Tag current;
    // Set by pass() while push dispatch is registered; a tap goes here instead of parking for a teppanyaki() wait.
    private volatile Consumer<Tag> push;
    // Signalled under its own monitor when a tap publishes a new card, waking a teppanyaki() worker in awaitTap.
    private final Object cardArrived = new Object();
    // Set once by close(): rejects later onResume and wakes a wait that would otherwise block forever.
    private volatile boolean closed;
    // Shown/hidden around a teppanyaki() wait: true asks the caller to prompt "tap a card", false to clear it.
    private volatile Consumer<Boolean> prompt = shown -> {};

    private AndroidKitchen(Activity activity, Preferences prefs, Function<BIBOSA, BIBOSA> decorate) {
        this.activity = activity;
        this.nfc = NfcAdapter.getDefaultAdapter(activity);
        this.prefs = prefs;
        this.decorate = decorate;
    }

    public static AndroidKitchen open(Activity activity) {
        return new AndroidKitchen(activity, new Preferences(), Function.identity());
    }

    // prefs fix the session configuration (exclusivity, freshness) before any card is touched; decorate
    // applies the same transport wrappers a session needs (GET RESPONSE, 6Cxx retry), identity for none.
    public static AndroidKitchen open(Activity activity, Preferences prefs, Function<BIBOSA, BIBOSA> decorate) {
        return new AndroidKitchen(activity, prefs, decorate);
    }

    // Pull: run the handler against the next card. Returns at once; the future completes on the reader-mode
    // worker once a tap lands and the handler has run. A card already in the field is taken without a wait.
    @Override
    public <T> CompletableFuture<T> teppanyaki(BiFunction<Chef, Preferences, T> handler) {
        claim();
        return CompletableFuture.supplyAsync(() -> {
            while (true) {
                Tag tag = current;
                if (tag == null) {
                    tag = awaitTap();
                }
                BIBOSA stack;
                try {
                    stack = connect(tag);
                } catch (IOException e) {
                    // Connect failed before any application APDU: this cached tap is dead. Clear it unless a
                    // newer tap already replaced it, then keep waiting for a fresh one.
                    if (e instanceof TagLostException) {
                        // The departure counterpart to onTagDiscovered: the card left before we could connect.
                        Log.d(TAG, "Cached tap's card left the field before connect");
                    } else {
                        Log.w(TAG, "Connect failed on cached tap", e);
                    }
                    if (current == tag) {
                        current = null;
                    }
                    continue;
                }
                try {
                    return runHandler(stack, handler);
                } finally {
                    stack.close();
                }
            }
        }, worker).whenComplete((r, t) -> release());
    }

    // Push: run the handler against every tap until the returned handle is closed. Each accepted tap connects
    // the card, runs the handler on the reader-mode worker, and delivers (dish, null) or (null, cause). The
    // handle stops dispatch; reader mode itself stays owned by onResume/onPause.
    @Override
    public <T> AutoCloseable pass(BiFunction<Chef, Preferences, T> handler, BiConsumer<Dish<T>, Throwable> results) {
        claim();
        push = tag -> {
            try {
                BIBOSA stack = connect(tag);
                try {
                    T value = runHandler(stack, handler);
                    results.accept(new Dish<>(value, stack.preferences()), null);
                } finally {
                    stack.close();
                }
            } catch (Throwable t) {
                results.accept(null, t);
            }
        };
        return () -> {
            push = null;
            release();
        };
    }

    // Mint a Chef over the composed stack (already decorated at connect) and run the handler with its session
    // facts. The caller closes the stack afterwards, applying the transport's baked-in disposition.
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

    // The caller's "tap a card" prompt, raised while teppanyaki() waits for a card and cleared after. Optional:
    // with none set, the wait is silent. Called on the waiting worker thread, not the UI thread.
    public void setPrompt(Consumer<Boolean> prompt) {
        this.prompt = prompt == null ? shown -> {} : prompt;
    }

    // False when the device has no NFC, so the caller can say so instead of waiting for a tap that never comes.
    public boolean available() {
        return nfc != null;
    }

    // False when NFC is present but switched off: a disabled adapter delivers no tap, so the caller can prompt
    // to enable it rather than waiting silently. Re-check on resume, as the switch can flip while the app is open.
    public boolean enabled() {
        return nfc != null && nfc.isEnabled();
    }

    // Bind reader mode; forward from the Activity's onResume. Every ISO-DEP tap while the screen is up is handed
    // here directly, the NDEF probe skipped so nothing else claims the card.
    public void onResume() {
        if (closed || nfc == null) {
            return;
        }
        nfc.enableReaderMode(activity, this::onTag,
                NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                        | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
    }

    // Unbind reader mode; forward from the Activity's onPause.
    public void onPause() {
        if (nfc != null) {
            nfc.disableReaderMode(activity);
        }
    }

    // Handle a launch or re-entry intent that carries a tag (the Activity was closed or backgrounded when tapped).
    public void onNewIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        var tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag.class);
        if (tag != null) {
            onTag(tag);
        }
    }

    // Keep the screen lit and shown over the lock screen while the Activity is up: a tap that wakes the phone
    // then lands on the Activity, and reader mode stays live (a paused Activity drops it, so foreground taps
    // would otherwise be missed). Call once in onCreate.
    public static void keepAwake(Activity activity) {
        activity.setShowWhenLocked(true);
        activity.setTurnScreenOn(true);
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // Reader-mode callback and intent path both land here, on the NFC callback thread. Only ISO-DEP tags are
    // kept. With a push handler registered the tap is dispatched to the worker, gated so a tap arriving mid-read
    // is dropped whole and the callback thread never runs the handler. Otherwise it is published as the card in
    // the field and any teppanyaki() waiting in awaitTap is woken; with none waiting it stays for the next call.
    private void onTag(Tag tag) {
        if (IsoDep.get(tag) == null) {
            return;
        }
        Consumer<Tag> sink = push;
        if (sink == null) {
            synchronized (cardArrived) {
                current = tag;
                cardArrived.notifyAll();
            }
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            try {
                sink.accept(tag);
            } finally {
                busy.set(false);
            }
        });
    }

    // Block for the next tap while the prompt is shown, until close() wakes it. A tap published into current
    // before or during the wait ends it; a card left in the field survives for the teppanyaki() rather than
    // being consumed here.
    private Tag awaitTap() {
        prompt.accept(true);
        try {
            synchronized (cardArrived) {
                while (current == null) {
                    if (closed) {
                        throw new IllegalStateException("kitchen closed while waiting for a card");
                    }
                    cardArrived.wait();
                }
                return current;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for a card", e);
        } finally {
            prompt.accept(false);
        }
    }

    // Final teardown: drop reader mode, forget the card in the field, wake a wait blocked for a tap that will
    // never come, and stop the worker. The Activity's own lifecycle owns onResume/onPause; an in-flight session,
    // holding its own IsoDep, is left to finish. Sessions self-close, so there is no connection here to close.
    @Override
    public void close() {
        closed = true;
        onPause();
        synchronized (cardArrived) {
            current = null;
            cardArrived.notifyAll();
        }
        worker.shutdownNow();
    }

    // Connect the tag for one session: a fresh IsoDep, the disposition the freshness contract calls for,
    // the contactless facts sidecar over it, then the decorator to compose the flexible bibo.
    private BIBOSA connect(Tag tag) throws IOException {
        IsoDep iso = IsoDep.get(tag);
        iso.connect();
        iso.setTimeout(5000);
        return decorate.apply(new BIBOSA(new AndroidBIBO(iso, disposition()), facts(iso)));
    }

    // The disconnect disposition, fixed at acquisition from the session configuration. Android cannot drop the
    // RF field, so close() can never force a power-on; a required power-on is supplied by the fresh tap itself.
    // The freshness contract still selects the value for parity with the PC/SC transport, where the configuration
    // picks UNPOWER over RESET. Every Android tap is a genuine power-on, so it defaults to RESET.
    private AndroidBIBO.Disposition disposition() {
        return prefs.get(CardInfo.FRESH)
                ? AndroidBIBO.Disposition.RESET
                : AndroidBIBO.Disposition.LEAVE;
    }

    // The contactless session facts, grown from the configuration spine and enhanced with what the tag reveals.
    // ATR is omitted over contactless. Every tap is a genuine power-on, so FRESH_TAP is set true; reader mode
    // holds the tag for the app alone, so EXCLUSIVE_HELD is set true.
    private Preferences facts(IsoDep iso) {
        var facts = prefs
                .with(CardInfo.READER_NAME, readerName())
                .with(CardInfo.NEGOTIATED_PROTOCOL, "T=CL")
                .with(CardInfo.FRESH_TAP, true)
                .with(CardInfo.EXCLUSIVE_HELD, true);
        var tag = iso.getTag();
        var uid = tag == null ? null : tag.getId();
        if (uid != null && uid.length > 0) {
            facts = facts.with(CardInfo.UID, HexBytes.b(uid));
        }
        var ats = ats(iso);
        if (ats != null && ats.length > 0) {
            facts = facts.with(CardInfo.ATS, HexBytes.b(ats));
        }
        if (tag != null && tag.getTechList() != null && tag.getTechList().length > 0) {
            facts = facts.with(AndroidBIBO.TAG_TYPE, techList(tag));
        }
        return facts;
    }

    // The ATS the reader saw: the ATS historical bytes over NFC-A (ISO-DEP on ISO/IEC 14443-3A), or the higher
    // layer response (the ATTRIB answer) over NFC-B. Android exposes no fuller ATS than these.
    private static byte[] ats(IsoDep iso) {
        var historical = iso.getHistoricalBytes();
        if (historical != null && historical.length > 0) {
            return historical;
        }
        return iso.getHiLayerResponse();
    }

    // The phone as the reader: manufacturer and model.
    private static String readerName() {
        return "%s %s".formatted(Build.MANUFACTURER, Build.MODEL);
    }

    // The tag's tech list with the android.nfc.tech package prefix stripped: "IsoDep, NfcA".
    private static String techList(Tag tag) {
        return Arrays.stream(tag.getTechList())
                .map(t -> t.substring(t.lastIndexOf('.') + 1))
                .collect(Collectors.joining(", "));
    }
}
