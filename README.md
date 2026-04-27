# `apdu4j` &middot; a modern take on smart cards

[![Build Status](https://github.com/martinpaljak/apdu4j/actions/workflows/robot.yml/badge.svg?branch=master)](https://github.com/martinpaljak/apdu4j/actions)
&nbsp;[![Maven version](https://img.shields.io/maven-metadata/v?label=javacard.pro%20version&metadataUrl=https%3A%2F%2Fmvn.javacard.pro%2Fmaven%2Fcom%2Fgithub%2Fmartinpaljak%2Fapdu4j-pcsc%2Fmaven-metadata.xml)](https://gist.github.com/martinpaljak/c77d11d671260e24eef6c39123345cae)
&nbsp;[![MIT licensed](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/martinpaljak/apdu4j/blob/master/LICENSE)
&nbsp;[![Made in Estonia](https://img.shields.io/badge/Made_in-Estonia-blue)](https://estonia.ee)

Modern Java 17+ smart-card library built on one functional interface, **BIBO**: `byte[] transceive(byte[])`.

> BIBO stands for "Bytes In, Bytes Out". Like `SCardTransmit`, `IsoDep.transceive()`. `CardChannel.transmit()` etc.

Decorators (timed logging, dumping, typed APDU access etc) stack as needed with `then()`.

Use `apdu4j-pcsc` for desktop PC/SC readers. It pulls everything else in transitively. Use `apdu4j-core` where
`javax.smartcardio` is not available or needed: Android, headless tests, embedded.

> [!TIP]
> Start with [Quick start](#quick-start), see [Modules](#modules) for the full picture, or browse
> the [wiki](https://github.com/martinpaljak/apdu4j/wiki).

## Quick start

Select a reader and send an APDU in 3 lines:

```java
Readers.select().

withCard().

accept(bibo ->{
var response = bibo.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00));
    System.out.

println("SW: %04X".formatted(response.getSW()));
        });
```

With logging and session recording:

```java
Readers.select().

withCard()
    .

log(System.out)
    .

dump(new FileOutputStream("session.dump"))
        .

run(bibo ->bibo.

transmit(new CommandAPDU("00A4040007A0000002471001")));
```

## Modules

| Module             | Java | Purpose                                                                             |
|--------------------|------|-------------------------------------------------------------------------------------|
| `apdu4j-core`      | 17   | `BIBO`, `BIBOSA`, APDU types, decorators, protocol handlers; no `javax.smartcardio` |
| `apdu4j-pcsc`      | 17   | PC/SC readers via `javax.smartcardio`, fluent `Readers` API, thread-safe sessions   |
| `apdu4j-pcsc-sim`  | 17   | Synthesized `javax.smartcardio` provider over a `BIBO`                              |
| `apdu4j-prefs`     | 17   | Typed `Preference` / `Preferences`                                                  |
| `apdu4j-apdulette` | 21   | Lazy, composable APDU recipes                                                       |
| `apdu4j-tool`      | 17   | CLI tool                                                                            |

## Core (`apdu4j-core`)

### BIBO

`BIBO` - `byte[] transceive(byte[]) throws BIBOException`, extending `AutoCloseable`. Equivalent to Android's
`IsoDep.transceive()`, PC/SC `SCardTransmit()`, or `javax.smartcardio` `CardChannel.transmit()`.

### CommandAPDU / ResponseAPDU

`CommandAPDU` - immutable ISO 7816-4 command record. Construct from fields, hex, or raw bytes, compatible with
`javax.smartcardio`:

```java
new CommandAPDU(0x00,0xA4,0x04,0x00,data)  // fields
new

CommandAPDU("00A4040007A0000002471001")     // hex string
```

`ResponseAPDU` - immutable response: `getSW()`, `getSW1()`, `getSW2()`, `getData()`, `getSWBytes()`.

### Decorators

Decorators chain with `then()`:

```java
BIBO transport = ...;  // any BIBO: PC/SC, Android NFC, mock, network
var bibo = transport
        .then(GetResponseWrapper::wrap)
        .then(RetryWithRightLengthWrapper::wrap)
        .then(b -> LoggingBIBO.wrap(b, System.out))
        .then(b -> DumpingBIBO.wrap(b, new FileOutputStream("session.dump")));
var response = bibo.transmit(new CommandAPDU("00A4040007A0000002471001"));
```

`apdu4j-core` ships `LoggingBIBO`, `DumpingBIBO`, `GetResponseWrapper`, `GetMoreDataWrapper` (ETSI 9F),
`RetryWithRightLengthWrapper` (6C retry), `LogicalChannelBIBO`, and `MockBIBO`. `BIBO` is a drop-in for code using
`javax.smartcardio` APDU types: `bibo.transmit(CommandAPDU)` mirrors `CardChannel.transmit(CommandAPDU)`. Change imports
and the rest works.

### Stateful sessions

`StatefulBIBO<S>` threads typed state through atomic wrap-send-unwrap cycles. State implements `AutoCloseable` for key
zeroing on close. Building block for SCP02/SCP03 secure channels; see the javadoc.

### BIBOSA

`BIBOSA` is `BIBO` with a typed `Preferences` sidecar, composed like WSGI middleware. Each `BIBOMiddleware` layer can
wrap the transport and contribute typed preferences (effective block size, protocol version, security parameters) that
downstream layers and callers query after the stack is built:

```java
var BLOCK_SIZE = Preference.of("blockSize", Integer.class, 255, true);

BIBOMiddleware secureChannel = stack -> {
    var session = openSecureChannel(stack.bibo(), keys);
    var prefs = stack.preferences().with(BLOCK_SIZE, session.maxPayload());
    return new BIBOSA(session, prefs);
};

var stack = new BIBOSA(transport)
        .then(GetResponseWrapper::wrap)   // Function<BIBO,BIBO>: preferences pass through
        .then(secureChannel);             // BIBOMiddleware: adds preferences

var response = stack.transmit(cmd);   // BIBOSA is a BIBO; transmit() is on BIBO
int blockSize = stack.preferences().get(BLOCK_SIZE);
```

Simple wrappers (`Function<BIBO, BIBO>`) work unchanged because `then()` passes preferences through. Only layers that
contribute metadata implement `BIBOMiddleware`. Typed keys come from `apdu4j-prefs` (
`Preference.of(name, type, default, readonly)`).

### Testing

Stub responses directly:

```java
var mock = MockBIBO.with("00A4040007A0000002471001", "6F10A5049F6501FF9000")
        .then("80CA9F7F00", "9000");
var response = mock.transmit(new CommandAPDU("00A4040007A0000002471001"));

assertEquals(0x9000,response.getSW());
```

Replay from a dump file:

```java
var mock = MockBIBO.fromDump(getClass().getResourceAsStream("/card.dump"));
```

`DumpingBIBO` writes a hex dump that `MockBIBO.fromDump()` reads back:

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

## PC/SC (`apdu4j-pcsc`): selecting the right reader

`Readers.select()` does the right thing when there is only one reader. Pass a name fragment or a 1-indexed number to
disambiguate, or wire your own typed preference keys to environment variables (`myapp.reader` resolves to
`MYAPP_READER`) or CLI parameters:

```java
var READER = Preference.of("myapp.reader", String.class, "", false);
var IGNORE = Preference.of("myapp.reader.ignore", String.class, "", false);

Readers.fromPreferences(Preferences.fromEnvironment(), READER, IGNORE)
        .withCard()
        .protocol("T=1")
        .log(System.out)
        .run(apdu ->{...});
```

Other knobs (`ignore`, `filter`, `exclusive`, `dump`, `whenReady`, `onCard`) are also available.
For raw `javax.smartcardio` access, use `.terminal()` and `.card()`.

## Apdulette (`apdu4j-apdulette`, Java 21)

Ongoing work on lazy, composable APDU interaction recipes. See [apdulette/README.md](apdulette/README.md).

## Get it now!

Pull from Maven (see [Usage from Java](#usage-from-java) below) or build from source:

```shell
git clone https://github.com/martinpaljak/apdu4j
cd apdu4j
./mvnw package
```

## Usage from Java

```xml

<dependency>
    <groupId>com.github.martinpaljak</groupId>
    <artifactId>apdu4j-pcsc</artifactId>
    <version>LATEST</version>
</dependency>
```

For headless / Android / test-only use (no `javax.smartcardio`), swap `apdu4j-pcsc` for `apdu4j-core`.

Add the repository to your `pom.xml`:

```xml

<repositories>
    <repository>
        <id>javacard-pro</id>
        <url>https://mvn.javacard.pro/maven/</url>
    </repository>
</repositories>
```

## Similar and related projects

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

## History

Extracted from [GlobalPlatformPro](https://github.com/martinpaljak/GlobalPlatformPro)
and [JavaCard](http://javacard.pro) work to keep low-level PC/SC code in one place. Also fills the gap of a Java command
line tool for APDU-level reader access (previously only available in C).

## Included/used open source projects

* [jnasmartcardio](https://github.com/martinpaljak/apdu4j-jnasmartcardio) for PC/SC access (CC0 / public domain)
* [picocli](https://picocli.info/) for parsing command line (Apache 2.0)
* [Launch4j](http://launch4j.sourceforge.net/) for generating .exe (BSD/MIT)

## License

* [MIT](./LICENSE)
* Annotated with SPDX for SBOM uses

## Contact

* martin@martinpaljak.net
