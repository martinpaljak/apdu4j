# apdulette-android

A `Terminal` over Android NFC reader mode. The same apdulette recipes that run on a PC/SC reader run
against a tapped contactless card, so a `Chef` written once reads on the desktop and on the phone.

One `NfcTerminal` is held by an `Activity` for the screen's life. Reader mode is the card source: a
tap sets the card in the field, and a `CardPresence` reconnects that card per session, so the several
sessions one card needs (an EMV card that lists two applications, where GET PROCESSING OPTIONS binds
the first) all run on the same tap.

`NfcTerminal` implements the platform-free `Terminal` interface: `next` (pull) and `each` (push). Its
Android lifecycle methods (`onResume`, `onPause`, `onIntent`, `setPrompt`, `available`, `enabled`,
`keepAwake`, `close`) sit beside those, so `get()` hands back the concrete type.

## Build

The module compiles only under the `android` profile and needs an Android SDK with the `android.jar`
platform set in `apdulette-android/pom.xml`:

    ANDROID_HOME=~/Library/Android/sdk ./mvnw -Pandroid -pl apdulette-android -am compile

`android.jar` is `system`-scoped and compile-only, so nothing Android leaks onto a non-Android
consumer of apdu4j.

## Integrate

Hold one terminal, forward the Activity lifecycle to it, and register `each` to read on every tap.

```java
public final class ScanActivity extends Activity {
    private NfcTerminal terminal;
    private AutoCloseable subscription;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Optional: land a lock-screen tap on this Activity and keep reader mode live.
        NfcTerminal.keepAwake(this);
        // The decorator adds the transport wrappers the card needs (GET RESPONSE, 6Cxx retry).
        terminal = NfcTerminal.get(this, s -> s.then(Transport::decorate));
        // Push: run the handler against every tap. The sink gets (dish, null) or (null, cause).
        subscription = terminal.each(
                card -> card.cook(recipe),
                (dish, err) -> runOnUiThread(() -> {           // touch views on the UI thread only
                    if (err != null) show(err);
                    else show(dish.value());
                }));
        terminal.onIntent(getIntent());   // a tap that launched the Activity while it was closed
    }

    @Override protected void onResume()  { super.onResume();  terminal.onResume(); }   // bind reader mode
    @Override protected void onPause()   { super.onPause();   terminal.onPause();  }   // unbind reader mode

    @Override protected void onDestroy() {
        super.onDestroy();
        try { subscription.close(); } catch (Exception ignored) {}   // stop dispatch
        terminal.close();                                            // release the card
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        terminal.onIntent(intent);        // a tap that re-entered a backgrounded Activity
    }
}
```

The `each` handler runs on a background thread, one tap at a time; taps arriving during a read are
dropped. Card I/O must not run on the UI thread. UI updates must run on the UI thread
(`runOnUiThread`).

## Reading a card

The handler gets a `CardPresence`. `card.preferences()` reports the contactless facts without opening
a session, so a card can be rejected before any APDU: `CardInfo.READER_NAME` (the phone),
`CardInfo.NEGOTIATED_PROTOCOL` (`T=CL`), `CardInfo.UID`, `CardInfo.ATS`, and `AndroidBIBO.TAG_TYPE`
(the NFC tech list). There is no ATR over contactless, so `CardInfo.ATR` is unset.

`card.cook(recipe)` runs one recipe as its own session. For a single recipe that is the whole read.

`card.session(fn)` opens one session and hands its `Chef` to `fn`; several `chef.cook(...)` or
`chef.serve(...)` inside that call share selection state without a reset between them. Between
successive `session` calls the card observes a reset, so one application's transient state does not
leak into the next:

```java
terminal.next(card -> {
    var apps = card.session(chef -> selectPse(chef));        // session 1
    var log  = card.session(chef -> readLog(chef, apps));    // session 2, fresh select
    return log;
});
```

## Waiting for a card

`each` is the push path: register it once and every tap runs the handler. `next` is the pull path:
it returns immediately and completes on a background worker once a tap lands and the handler has run.
A card already in the field is taken without a wait. Only one handler runs per terminal at a time;
`next` or `each` while one is active throws `IllegalStateException`.

Register a prompt to show a "tap a card" hint while `next` waits, and set a deadline with `orTimeout`:

```java
terminal.setPrompt(shown -> runOnUiThread(() -> prompt.setVisibility(shown ? VISIBLE : GONE)));
terminal.next(card -> card.cook(recipe))
        .orTimeout(30, TimeUnit.SECONDS)
        .whenComplete((result, err) -> runOnUiThread(() -> {
            if (err != null) show(err);
            else show(result);
        }));
```

`next` does not block the caller, so it is safe to call from the UI thread; the returned
`CompletableFuture` completes off it.

## NFC availability

`available()` is false when the device has no NFC hardware. `enabled()` is false when NFC is present
but switched off, which delivers no tap. Re-check `enabled()` in `onResume`, as the switch can flip
while the app is open:

```java
if (!terminal.available()) {
    show("This phone has no NFC.");
} else if (!terminal.enabled()) {
    show("NFC is off. Turn it on in settings.");
}
```

## Manifest

Reader mode needs the NFC permission. Require the hardware to keep the app off devices without it.
To launch the Activity on a tap while it is closed, add a `TECH_DISCOVERED` filter for `IsoDep`.

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="true" />

<activity android:name=".ScanActivity" android:launchMode="singleTop" android:exported="true">
    <intent-filter>
        <action android:name="android.nfc.action.TECH_DISCOVERED" />
    </intent-filter>
    <meta-data
        android:name="android.nfc.action.TECH_DISCOVERED"
        android:resource="@xml/nfc_tech_filter" />
</activity>
```

```xml
<!-- res/xml/nfc_tech_filter.xml -->
<resources>
    <tech-list>
        <tech>android.nfc.tech.IsoDep</tech>
    </tech-list>
</resources>
```
