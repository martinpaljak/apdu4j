# apdu4j

Command line tool and library of useful Java 1.7+ classes for working with smart cards and smart card readers via [JSR268](https://jcp.org/en/jsr/detail?id=268) (commonly known as [javax.smartcardio](https://docs.oracle.com/javase/8/docs/jre/api/security/smartcardio/spec/javax/smartcardio/package-summary.html)). While focus is on desktop PC/SC readers, some code can be re-used with arbitrary "APDU-command-response-ish" interfaces, either as [CommandAPDU](https://docs.oracle.com/javase/8/docs/jre/api/security/smartcardio/spec/javax/smartcardio/CommandAPDU.html)/[ResponseAPDU](https://docs.oracle.com/javase/8/docs/jre/api/security/smartcardio/spec/javax/smartcardio/ResponseAPDU.html) pairs or plain byte arrays. 

## Features
* PinPad support (PC/SC v2 part 10 / CCID)
* Fixes all the stupid things with Java on non-windows platforms: OSX, Debian, Ubuntu, Fedora, CentOS, FreeBSD.
* Java tools for convenient APDU logging, PIN handling and more
* Bundles jnasmartcardio in the command line tool for easy testing

#### Jump to ...
* [Download](#get-it-now)
* [Usage from command line](#usage-from-command-line)
* [Usage from Java](#usage-from-java)
* [Similar and related projects](#similar-and-related-projects)
* [Contact](#contact)

### Get it now!
* Download latest pre-built .JAR or .EXE from [release area](https://github.com/martinpaljak/apdu4j/releases)
* Or fetch from github and build it yourself, it is really easy (more instructions in [CONTRIBUTING](./CONTRIBUTING.md):

```shell
git clone https://github.com/martinpaljak/apdu4j
cd apdu4j
ant
```

* Status
  * Travis - [![Build Status](https://travis-ci.org/martinpaljak/apdu4j.png?branch=master)](https://travis-ci.org/martinpaljak/apdu4j)
  * Coverity - [![Coverity status](https://scan.coverity.com/projects/3664/badge.svg?flat=1)](https://scan.coverity.com/projects/3664/)

### Usage from command line
 * Before you begin:
   * You can run the command line utility anywhere where Java runs, like this:

            java -jar apdu4j.jar
   * But it is easier to add an alias or use a wrapper.            
   * On Linux add an alias to the shell like this:

            alias sc="java -jar $(PWD)/apdu4j.jar"
            # Now you can avoid typing java -jar and sc works from any folder
            sc -h

   * On Windows just use pre-packaged ```apdu4j.exe``` like this or rename it:

            apdu4j.exe -h

 * Display all options:

        sc -h
 
 * List readers:

        sc -l
   
   Will produce something like
   ```
[ ] Gemalto Ezio Shield 01 00
[*] ACS ACR 38U-CCID 02 00
```
   The presence of a card or token is indicated with the asterisk
 * Be verbose:

        sc -l -v
   Will produce:
   ```
# Using sun.security.smartcardio.SunPCSC - SunPCSC version 1.7
# Found 3 terminals
[ ] [VMD] Gemalto Ezio Shield 01 00
[*] [   ] ACS ACR 38U-CCID 02 00
          3BF91300008131FE454A434F503234325233A2
[ ] [   ] ACS ACR 38U-CCID 03 00
```
   In addition to the ATR of the inserted card below the reader, PinPad features of the terminal are shown: V - PIN verification, M - PIN modification, D - display

 * Take you directly to the [online ATR database](http://smartcard-atr.appspot.com/)

        sc -l -v -w

 * Use a virtual smart card reader provider (NB! See [Pro Tips](#pro-tips)):

        sc -p com.example.VirtualTerminalProvider -lv

 * Send the APDU ```00A40C0000``` to the card:

        sc -a 00A40C0000

 * The same with forced T=0 protocol (similar for T=1):

        sc -t0 -a 00A40C0000

 * The same, with an additional APDU, while dumping everything to ```card.dump```
 
        sc -t0 -a 00A40C0000 -a 80:01:04:00:00 -dump card.dump

 * SunPCSC - use specific PC/SC library:

        sc -lib /usr/local/lib/pcsclite.so -l

 * SunPCSC - don't issue `GET RESPONSE` commands:

        sc -no-get-response -a 00A4040000 -v

 * Show APDU-s sent to the card (using ```LoggingCardTerminal```):
   
   add ```-debug``` or ```-d``` to your command

 * Be verbose:
   
   add ```-verbose``` or ```-v``` to your command


#### Pro Tips
 * Running with extended classpath

    For some stupid reason Java does not allow to use both ```-jar``` and ```-cp``` in the same command, so you need to manually specify the full classpath and main class, together with any parameters you want to pass to the utility. The main class of the ```sc``` tool is ```apdu4j.SCTool```, so something along the lines:

        java -cp someother.jar:apdu4j.jar apdu4j.SCTool -p org.someother.APDUClass -l

### Usage from Java
Before anything make sure you set the necessary properties to make javax.smartcardio work without tuning:

```java
import apdu4j.TerminalManager;
TerminalManager.fixPlatformPaths();
```

#### LoggingCardTerminal
 * Show a debugging trace (like ```-d```) of PC/SC calls and exhanged APDU-s with timing on System.out:
 
```java
import apdu4j.LogginCardTerminal;
        
TerminalFactory f = TerminalFactory.getDefault();
CardReader r = f.terminals().terminal("Your Smart Card Reader Name");
reader = LoggingCardTerminal.getInstance(reader);
// Now use javax.smartcardio as you normally do
```

 * This will give you output similar to:
```
SCardConnect("SCM Microsystems Inc. SCR 355 00 00", T=*) -> T=1, 3BFC180000813180459067464A00680804000000000E
SCardBeginTransaction("SCM Microsystems Inc. SCR 355 00 00")
A>> T=1 (4+0000) 00A40400 00 
A<< (0018+2) (17ms) 6F108408A000000003000000A5049F6501FF 9000
A>> T=1 (4+0000) 80CA9F7F 00 
A<< (0000+2) (11ms) 6A88
```

 * Dump all APDU communication with a card to a file:

```java
import apdu4j.LogginCardTerminal;
        
TerminalFactory tf = TerminalFactory.getDefault();
CardReader r = tf.terminals().terminal("Your Smart Card Reader Name");
FileOutputStream o = new FileOutputStream(new File("card.dump"));
reader = LoggingCardTerminal.getInstance(reader, o);
// Now use javax.smartcardio as you normally do
```
 * This will make a dump file similar to this:

```
# Generated on Wed, 31 Dec 2014 18:10:35 +0200 by apdu4j
# Using SCM Microsystems Inc. SCR 355 00 00
# ATR: 3BFE1800008031FE4553434536302D43443038312D6E46A9
# PROTOCOL: T=1
#
# Sent
00A4040000
# Received in 24ms
6F108408A000000003000000A5049F6501FF9000
# Sent
80500000084D080A4D1C5EBC92
# Received in 70ms
00001248950019F738700103002421796B41BB3B7014659BFC8A54B2479000
```
 
#### APDUReplayProvider
This is a TerminalFactory that presents a single reader that will reply to commands from a session stored with `LoggingCardTerminal`. This allows to test certain code in a way that doesn't require physical readers or tokens.

```java
import apdu4j.APDUReplayProvider;

FileInputStream f = new FileInputStream(new File("card.dump"));
TerminalFactory tf = TerminalFactory.getInstance("PC/SC", f, new APDUReplayProvider());
// Now use javax.smartcardio as you normally do
// There is only one terminal exposed
```

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


### History and motivation

When working with [GlobalPlatformPro](https://github.com/martinpaljak/GlobalPlatformPro) and [JavaCard](http://javacard.pro)-s, some low level code wanted to sneak into projects where it did not belong, so it made sense to capture it into a separate library. Also, while command line tools for accessing readers on APDU (PC/SC) level existed for C, nothing was available for doing the same via Java stack, thus the need for a DWIM command line tool.


### Included/used open source projects

 * [JOpt Simple](http://pholser.github.io/jopt-simple/) for parsing command line (MIT)
 * [Launch4j](http://launch4j.sourceforge.net/) for generating .exe (BSD/MIT)
 * [jnasmartcardio](https://github.com/jnasmartcardio/jnasmartcardio) for PC/SC access (CC0 / public domain)

### License

 * MIT

### Contact 

* martin@martinpaljak.net
