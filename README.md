# apdu4j

Command line utility and useful Java code library for working with smart cards and smart card readers via [JSR268](https://jcp.org/en/jsr/detail?id=268) (commonly known as [javax.smartcardio](https://docs.oracle.com/javase/8/docs/jre/api/security/smartcardio/spec/javax/smartcardio/package-summary.html)). While focus is on desktop PC/SC readers, some code can be re-used with arbitrary "APDU-command-response-ish" interfaces.

#### Jump to ...
* [Download](#get-it-now)
* [Usage from command line](#usage-from-command-line)
* [Usage from Java](#usage-from-java)
* [Similar and related projects](#similar-projects)
* [Contact](#contact)

### Get it now!
* Download latest pre-built JAR or .EXE from [release area](https://github.com/martinpaljak/apdu4j/releases)
* Or fetch from github and build it yourself, it is really easy:

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

   * On Windows just use pre-packaged ```sc.exe``` like this:

            sc.exe -info
 
 * Display all options:

        sc -h
 
 * List readers:

        sc -l

 * Be verbose:

        sc -l -v

 * Use a virtual smart card reader provider:

        sc -p com.example.VirtualTerminalProvider -lv

 * Send the APDU ```00A40C0000``` to the card:

        sc -a 00A40C0000

 * The same with forced T=0 protocol (similar for T=1):

        sc -t0 -a 00A40C0000

 * The same, with additional APDU and dumping everything to ```card.dump```
 
        sc -t0 -a 00A40C0000 -a 80:01:04:00:00 -dump card.dump 

 * Show APDU-s sent to the card:
   
   add ```-debug``` or ```-d``` to your command

 * Be verbose:
   
   add ```-verbose``` or ```-v``` to your command

### Usage from Java
#### LoggingCardTerminal
 * Show a debugging trace of PC/SC calls and exhanged APDU-s with timing on System.out:
 
```java
import apdu4j.LogginCardTerminal;
        
TerminalFactory f = TerminalFactory.getDefault();
CardReader r = f.terminals().terminal("Your Smart Card Reader Name");
reader = LoggingCardTerminal.getInstance(reader);
// Now use javax.smartcardio as you normally do
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

 
#### APDUReplayProvider
```java
import apdu4j.APDUReplayProvider;

FileInputStream f = new FileInputStream(new File("card.dump"));
TerminalFactory tf = TerminalFactory.getInstance("PC/SC", f, new APDUReplayProvider());
// Now use javax.smartcardio as you normally do
// There is only one terminal exposed
```


### Similar and related projects
 * SCUBA (LGPL) - http://scuba.sourceforge.net/
   * written in Java 
   * no command line utility
   * has *Provider*-s for weird hardware
 * jnasmartcardio (CC0) - https://github.com/jnasmartcardio/jnasmartcardio
   * written in Java
   * provides a "better" wrapper for system PC/SC service with JNA
   * used by apdu4j
 * OpenCard Framework (OPEN CARD CONSORTIUM SOURCE LICENSE) - http://www.openscdp.org/ocf/
   * written in Java
   * really old (pre-2000, comparable to CT-API)
   * no command line utility
 * OpenSC (opensc-tool, LGPL) - https://github.com/OpenSC/OpenSC
   * written in C
   * related to rest of OpenSC, but allows to send APDU-s from command line with ```opensc-tool -s XX:XX:XX:XX```

### License

 * MIT

### Included/used open source projects

 * [JOpt Simple](http://pholser.github.io/jopt-simple/) for parsing command line (MIT)
 * [Launch4j](http://launch4j.sourceforge.net/) for generating .exe (BSD/MIT)
 * [jnasmartcardio](https://github.com/jnasmartcardio/jnasmartcardio) for PC/SC access (CC0 / public domain)

### History

When working with [GlobalPlatform](https://github.com/martinpaljak/GlobalPlatform) and [JavaCard](http://javacard.pro)-s, some low level code wanted to sneak into projects where it did not belong, so it made sense to capture it into a separate library. Also, while command line tools for accessing readers on APDU (PC/SC) level existed for C, nothing was available for doing the same via Java stack, thus the need for a DWIM command line util.

### Contact 

* martin@martinpaljak.net
