# apdu4j

[![Build Status](https://github.com/martinpaljak/apdu4j/actions/workflows/robot.yml/badge.svg?branch=master)](https://github.com/martinpaljak/apdu4j/actions)
&nbsp;[![Maven version](https://img.shields.io/maven-metadata/v?label=javacard.pro%20version&metadataUrl=https%3A%2F%2Fmvn.javacard.pro%2Fmaven%2Fcom%2Fgithub%2Fmartinpaljak%2Fapdu4j-pcsc%2Fmaven-metadata.xml)](https://gist.github.com/martinpaljak/c77d11d671260e24eef6c39123345cae)
&nbsp;[![MIT licensed](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/martinpaljak/apdu4j/blob/master/LICENSE)
&nbsp;[![Made in Estonia](https://img.shields.io/badge/Made_in-Estonia-blue)](https://estonia.ee)

Java 17+ library for smart card communication built on a single interface - `BIBO` ("Bytes In, Bytes Out") -
`byte[] transceive(byte[])`. Logging, session recording, protocol handling (auto GET RESPONSE, Le retry), and typed APDU
access are stackable `BIBO` decorators chained with `then()`.

Most users need two modules: `apdu4j-core` has the `BIBO` interface, APDU types, decorators, and protocol handlers with
no `javax.smartcardio` dependency - works on Android, in tests, or anywhere Java runs. `apdu4j-pcsc` adds PC/SC hardware
access with thread-safe readers and a fluent `Readers.select().withCard().run(apdu -> ...)` API.
Lazy APDU composition (`apdu4j-apdulette`, Java 21) is available as a separate module.
A [command line tool](#usage-from-command-line) is included for reader listing and APDU scripting.

Record sessions with `DumpingBIBO`, replay in tests with `MockBIBO.fromDump()` - no physical card reader needed.

## Features

* PinPad support (PC/SC v2 part 10 / CCID)
* Fixes Java smart card issues on non-Windows platforms: OSX, Debian, Ubuntu, Fedora, CentOS, FreeBSD
* Java tools for convenient APDU logging, PIN handling and more
* Bundles [jnasmartcardio][jnasmartcardio] in the command line tool for a reliable `javax.smartcardio` implementation
  with reader locking

#### Jump to ...

* [Quick start](#quick-start)
* [Architecture](#architecture)
* [Library usage](#usage-from-java)
* [Download](#get-it-now)
* [Usage from command line](#usage-from-command-line)
* [Similar and related projects](#similar-and-related-projects)
* [Wiki](https://github.com/martinpaljak/apdu4j/wiki)
* [Contact](#contact)

## Quick start

Send an APDU in 3 lines:

```java
Readers.select().withCard().accept(apdu -> {
    var response = apdu.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00));
    System.out.println("SW: %04X".formatted(response.getSW()));
});
```

With logging and session recording:

```java
Readers.select().withCard()
    .log(System.out)
    .dump(new FileOutputStream("session.dump"))
    .run(apdu -> apdu.transmit(new CommandAPDU("00A4040007A0000002471001")));
```

## Architecture

### Modules

```
apdu4j-pcsc        PC/SC readers, fluent API, thread safety     (Java 17)
    |
apdu4j-core        BIBO interface, APDU types, decorators       (Java 17)
apdu4j-prefs       Typed Preference/Preferences system          (Java 17)

apdu4j-apdulette   Lazy, composable APDU recipes                (Java 21)
    |
    +-- apdu4j-core, apdu4j-prefs
```

Use `apdu4j-pcsc` for desktop applications (pulls in everything transitively). Use `apdu4j-core` alone for Android,
embedded, or test-only scenarios where `javax.smartcardio` is not available.

### Layers

```
Apdulette            Recipe, Chef, Cookbook (declarative APDU)    -- apdu4j-apdulette (Java 21)
Fluent Reader API    Readers.select()...run(apdu -> ...)         -- apdu4j-pcsc
PC/SC Integration    TerminalManager, CardBIBO, PCSCReader       -- apdu4j-pcsc
Stateful Sessions    StatefulBIBO (wrap/unwrap state threading)  -- apdu4j-core
Protocol Handlers    GetResponseWrapper, RetryWithRightLength    -- apdu4j-core
Middleware           BIBOSA, BIBOMiddleware (BIBO+Preferences)   -- apdu4j-core
BIBO Decorators      LoggingBIBO, DumpingBIBO, APDUBIBO, Mock   -- apdu4j-core
Core Types           BIBO, CommandAPDU, ResponseAPDU, HexBytes   -- apdu4j-core
Preferences          Preference, Preferences, PreferenceProvider -- apdu4j-prefs
```

### Core Types (`apdu4j-core`)

`BIBO` - `byte[] transceive(byte[])`, extending `AutoCloseable`. Equivalent to Android's `IsoDep.transceive()`, PC/SC
`SCardTransmit()`, or `javax.smartcardio` `CardChannel.transmit()`.

`CommandAPDU` - immutable ISO 7816-4 command record, handles all short and extended length encoding cases. Construct
from fields, hex, or raw bytes:

```java
new CommandAPDU(0x00, 0xA4, 0x04, 0x00, data)  // fields
new CommandAPDU("00A4040007A0000002471001")     // hex string
```

`ResponseAPDU` - immutable response: `getSW()`, `getSW1()`, `getSW2()`, `getData()`, `getSWBytes()`.

### Composable Decorators

BIBO decorators combine like Unix pipes - each layer wraps the next, processing only bytes with no shared state
between them:

```
App -> LoggingBIBO -> DumpingBIBO -> GetResponseWrapper -> CardBIBO -> PC/SC
          |                |               |                  |
       log APDU      record hex      chain 61xx          SCardTransmit
```

Chain with `then()`:

```java
BIBO transport = ...;  // any BIBO - PC/SC, Android NFC, mock, network
var bibo = transport
        .then(GetResponseWrapper::wrap)
        .then(RetryWithRightLengthWrapper::wrap)
        .then(b -> LoggingBIBO.wrap(b, System.out))
        .then(b -> DumpingBIBO.wrap(b, new FileOutputStream("session.dump")));
var apdu = new APDUBIBO(bibo);
```

| Decorator                                | Purpose                                             |
|------------------------------------------|-----------------------------------------------------|
| `LoggingBIBO.wrap(bibo, out)`            | Human-readable APDU trace with timing               |
| `DumpingBIBO.wrap(bibo, out)`            | Machine-readable hex dump for replay                |
| `GetResponseWrapper.wrap(bibo)`          | Auto-chains GET RESPONSE on SW1=61                  |
| `GetMoreDataWrapper.wrap(bibo)`          | Auto-chains on SW1=9F (ETSI)                        |
| `RetryWithRightLengthWrapper.wrap(bibo)` | Retries with correct Le on SW1=6C                   |
| `APDUBIBO(bibo)`                         | Typed `CommandAPDU` / `ResponseAPDU` over raw BIBO  |
| `MockBIBO`                               | Test stub with fluent builder and dump file replay  |
| `StatefulBIBO`                           | Threads session state through wrap/unwrap per APDU  |
| `BIBOSA`                                 | BIBO + typed `Preferences` sidecar for middleware   |

`APDUBIBO` is a drop-in for code using `javax.smartcardio` APDU types - just change imports. The fluent Reader API
delivers `APDUBIBO` directly from `run()`, `accept()`, and `whenReady()`, so typed APDU access requires no wrapping.

### Middleware with Preferences

`BIBOSA` combines like WSGI middleware: the same byte pipe as `BIBO`, plus a typed `Preferences` sidecar. Each
`BIBOMiddleware` layer can wrap the transport and contribute typed preferences -- effective block size, protocol version,
security parameters -- that downstream layers and callers query after the stack is built:

```java
var BLOCK_SIZE = Preference.of("blockSize", Integer.class, 255, true);

BIBOMiddleware secureChannel = stack -> {
    var session = openSecureChannel(stack.bibo(), keys);
    var prefs = stack.preferences().with(BLOCK_SIZE, session.maxPayload());
    return new BIBOSA(session, prefs);
};

var stack = new BIBOSA(transport)
        .then(GetResponseWrapper::wrap)   // Function<BIBO,BIBO> -- preferences pass through
        .then(secureChannel);             // BIBOMiddleware -- adds preferences

var apdu = new APDUBIBO(stack);       // BIBOSA is a BIBO
int blockSize = stack.preferences().get(BLOCK_SIZE);
```

Simple wrappers (`Function<BIBO, BIBO>`) work unchanged -- `then()` passes preferences through. Only layers that need to
contribute metadata implement `BIBOMiddleware`.

Core-only (no PC/SC) - wrap a raw `BIBO` explicitly:

```java
BIBO transport = getTransportFromSomewhere();
var apdu = new APDUBIBO(LoggingBIBO.wrap(transport, System.out));
```

### Stateful Sessions

`StatefulBIBO<S>` threads a typed state value `S` through pluggable `Wrap` and `Unwrap` functions on each `transceive`.
Wrap transforms the outgoing command (add MAC, encrypt data, set CLA=0x84) and evolves state. Unwrap transforms the
incoming response (verify MAC, decrypt) and evolves state again. The two together form one atomic state step:

```java
record SCPState(int counter, byte[] chainingValue, byte[] macKey) implements AutoCloseable {
    @Override
    public void close() { Arrays.fill(macKey, (byte) 0); }
}

var scp = new StatefulBIBO<>(transport,
    new SCPState(0, new byte[16], sessionMacKey),
    (cmd, state) -> { /* wrap: compute MAC, increment counter, return Stateful<CommandAPDU, SCPState> */ },
    (resp, state) -> { /* unwrap: verify RMAC, return Stateful<ResponseAPDU, SCPState> */ });
```

State is committed only after the full wrap-send-unwrap cycle completes - if the transport throws or unwrap fails, the
counter stays at its pre-cycle value. This prevents desynchronizing with the card on transport errors.

`StatefulBIBO` does not own the underlying transport - closing a session zeros keys (via `AutoCloseable` state) but
leaves the `BIBO` open for further use. Multiple secure channel sessions can run sequentially over the same transport.
In practice, the SCP factory wraps the result in `BIBOSA` to attach block size and other session metadata:

```java
var scp = new StatefulBIBO<>(transport, initialState, wrap, unwrap);
var stack = new BIBOSA(scp, prefs.with(BLOCK_SIZE, computeBlockSize(session)));
```

### Apdulette (`apdu4j-apdulette`, Java 21)

Ongoing work on lazy, composable APDU interaction recipes - see [apdulette/README.md](apdulette/README.md).

### Testing with MockBIBO

Stub responses directly:

```java
var mock = MockBIBO.with("00A4040007A0000002471001", "6F10A5049F6501FF9000")
        .then("80CA9F7F00", "9000");
var apdu = new APDUBIBO(mock);
var response = apdu.transmit(new CommandAPDU("00A4040007A0000002471001"));

assertEquals(0x9000, response.getSW());
```

Replay from a dump file:

```java
var mock = MockBIBO.fromDump(getClass().getResourceAsStream("/card.dump"));
```

### Fluent Reader API (`apdu4j-pcsc`)

* `Readers.select()`, `.select("Yubikey")`, `.ignore("CCID")`, `.filter(...)`, `.withCard()`
* `.protocol("T=1")`, `.exclusive()`, `.log(out)`, `.dump(out)`, `.transactions(false)`
* `Readers.list()`, `.list()` - `List<PCSCReader>` snapshot of available readers
* `.run(apdu -> ...)`, `.accept(apdu -> ...)`, `.whenReady(fn)`, `.whenReady(timeout, fn)`

Reader selection via environment variables:

```java
Readers.fromEnvironment("READER", "READER_IGNORE")
    .withCard()
    .protocol("T=1")
    .log(System.out)
    .run(apdu -> { ... });
```

For raw `javax.smartcardio` access, use `.terminal()` and `.card()`.

### Get it now!

* Download pre-built .JAR or .EXE from [releases](https://github.com/martinpaljak/apdu4j/releases)
* Or build from source:

```shell
git clone https://github.com/martinpaljak/apdu4j
cd apdu4j
./mvnw package
```

### Usage from Java

For desktop (PC/SC) applications:

```xml
<dependency>
    <groupId>com.github.martinpaljak</groupId>
    <artifactId>apdu4j-pcsc</artifactId>
    <version>LATEST</version>
</dependency>
```

For core-only (Android, embedded, testing - no `javax.smartcardio`):

```xml
<dependency>
    <groupId>com.github.martinpaljak</groupId>
    <artifactId>apdu4j-core</artifactId>
    <version>LATEST</version>
</dependency>
```

Add the repository to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>javacard-pro</id>
        <url>https://mvn.javacard.pro/maven/</url>
    </repository>
</repositories>
```

#### LoggingBIBO

* Show a debugging trace of APDU-s with timing on System.out:

```java
Readers.select().withCard()
    .log(System.out)
    .accept(apdu -> {
        apdu.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00));
    });
```

* Output:

```
>> 00A40400 00
<< (12ms) 6F108408A000000003000000A5049F6501FF 9000
>> 80CA9F7F 00
<< (11ms) 6A88
```

* For lower-level javax.smartcardio tracing (PC/SC calls, transactions), use `LoggingCardTerminal`:

```
SCardConnect("SCM Microsystems Inc. SCR 355 00 00", T=*) -> T=1, 3BFC180000813180459067464A00680804000000000E
SCardBeginTransaction("SCM Microsystems Inc. SCR 355 00 00")
A>> T=1 (4+0000) 00A40400 00
A<< (0018+2) (17ms) 6F108408A000000003000000A5049F6501FF 9000
A>> T=1 (4+0000) 80CA9F7F 00
A<< (0000+2) (11ms) 6A88
```

#### Dumping APDU communication

* Dump all APDU communication with a card to a file:

```java
Readers.select().withCard()
    .dump(new FileOutputStream("card.dump"))
    .accept(apdu -> {
        apdu.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00));
    });
```

* Dump file format:

```
# ATR: 3BFE1800008031FE4553434536302D43443038312D6E46A9
# PROTOCOL: T=1
00A4040000
# 24ms
6F108408A000000003000000A5049F6501FF9000
80500000084D080A4D1C5EBC92
# 70ms
00001248950019F738700103002421796B41BB3B7014659BFC8A54B2479000
```

Dump files can be replayed in tests with `MockBIBO.fromDump()`.

### Usage from command line

* Run the command line tool:

        java -jar apdu4j.jar
    * On Linux, add an alias:

          alias sc="java -jar $(PWD)/apdu4j.jar"
          sc -h

    * On Windows, use the pre-packaged `apdu4j.exe`:

          apdu4j.exe -h

* Display all options:

      sc -h

* List readers:

      sc -l

      [ ] Gemalto Ezio Shield 01 00
      [*] ACS ACR 38U-CCID 02 00

  `*` indicates a card is present in the reader.
* Be verbose:

      sc -l -v

      # Using jnasmartcardio.Smartcardio - JNA2PCSC version 0.2
      # Found 4 terminals
      [X] [   ] Yubico Yubikey 4 U2F+CCID
                3BF81300008131FE15597562696B657934D4
      [ ] [VMD] Gemalto Ezio Shield 01 00
      [*] [   ] ACS ACR 38U-CCID 02 00
                3BF91300008131FE454A434F503234325233A2
      [ ] [   ] ACS ACR 38U-CCID 03 00

  Shows ATR below each reader. PinPad features: V - PIN verification, M - PIN modification, D - display. `X` indicates
  exclusive use by another application.

* Open the [online ATR database](http://smartcard-atr.appspot.com/) for listed cards:

      sc -l -v -w

* Use a virtual smart card reader provider (format for `-p` is `jar:class:args`, where `args` part can be URL-encoded):

      sc -p some.jar:com.example.VirtualTerminalProvider:tcp%3A%2F%2F192.168.1.1%3A7000 -lv

* Send the APDU `00A40C0000` to the card:

      sc -a 00A40C0000

* The same with forced T=0 protocol (similar for T=1):

      sc -t0 -a 00A40C0000

* The same, with an additional APDU, while dumping everything to `card.dump`

      sc -t0 -a 00A40C0000 -a 80:01:04:00:00 -dump card.dump

* SunPCSC - use specific PC/SC library:

      sc -lib /usr/local/lib/pcsclite.so -l

* SunPCSC - don't issue `GET RESPONSE` commands:

      sc -no-get-response -a 00A4040000 -v

* Show APDU-s sent to the card (via `LoggingCardTerminal`): add `-debug` or `-d`

* Verbose output: add `-verbose` or `-v`

### Similar and related projects

* SCUBA (LGPL) - http://scuba.sourceforge.net/
    * :| written in Java
    * :( no command line utility
    * :) has *Provider*-s for weird hardware
* jnasmartcardio (CC0) - https://github.com/jnasmartcardio/jnasmartcardio
    * :| written in Java
    * :) provides a "better" wrapper for system PC/SC service with JNA as a *Provider*
    * :) used by apdu4j
* OpenCard Framework (OPEN CARD CONSORTIUM SOURCE LICENSE) - http://www.openscdp.org/ocf/
    * :| written in Java
    * :( really old (pre-2000, comparable to CT-API)
    * :( no command line utility
* intarsys smartcard-io (BSD) - https://github.com/intarsys/smartcard-io
    * :| written in Java
    * :| similar to jnasmartcardio (alternative native *Provider*)
* OpenSC (opensc-tool, LGPL) - https://github.com/OpenSC/OpenSC
    * :| written in C
    * :| related to rest of OpenSC, but allows to send APDU-s from command line with ```opensc-tool -s XX:XX:XX:XX```
* Countless other apdu/script tools
    * :| written in different languages
    * :| use different input formats and script files
    * :| just FYI

### History

Extracted from [GlobalPlatformPro](https://github.com/martinpaljak/GlobalPlatformPro)
and [JavaCard](http://javacard.pro) work to keep low-level PC/SC code in one place. Also fills the gap of a Java command
line tool for APDU-level reader access (previously only available in C).

### Included/used open source projects

* [picocli](https://picocli.info/) for parsing command line (Apache 2.0)
* [Launch4j](http://launch4j.sourceforge.net/) for generating .exe (BSD/MIT)
* [jnasmartcardio][jnasmartcardio] for PC/SC access (CC0 / public domain)

### License

* [MIT](./LICENSE)

### Contact

* martin@martinpaljak.net

[TerminalFactory]: https://docs.oracle.com/javase/8/docs/jre/api/security/smartcardio/spec/javax/smartcardio/TerminalFactory.html

[jnasmartcardio]: https://github.com/jnasmartcardio/jnasmartcardio
