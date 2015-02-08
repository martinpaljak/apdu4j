# Working with apdu4j and contributing
 * All patches require MIT license

## Building the software
Simple `ant` will produce working results if you have the dependencies.

### Debian/Ubuntu
 * Install dependencies: `apt-get install --no-install-recommends libccid openjdk-7-jdk git ant`

### Fedora/CentOS
 * Install dependencies: `yum install pcsc-lite-ccid java-1.8.0-openjdk git ant`
 * Start pcscd service: `service pcscd start`

### FreeBSD
 * Install dependencies: `pkg install devel/libccid java/openjdk7 devel/apache-ant devel/git`

## Building Windows executable
 * Download [launch4j](http://launch4j.sourceforge.net/) and extract a version matching your host platform into `ext/launch4j`
 * Run `ant windist`
