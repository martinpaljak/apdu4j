# code.
-injars build

# libraries.
-injars lib/jopt-simple-4.9.jar(!META-INF/**)
-injars lib/json-simple-1.1.1.jar(!META-INF/**)
-injars lib/bcprov-jdk15on-155.jar(!META-INF/**)

# slf4j.
-injars lib/slf4j-api-1.7.13.jar
-dontwarn org.slf4j.**
-libraryjars lib/slf4j-simple-1.7.13.jar

# JNA is library because we package everything back in
-libraryjars ext/jnasmartcardio/jnasmartcardio.jar
-libraryjars  <java.home>/lib/rt.jar
-libraryjars  <java.home>/lib/jce.jar
-outjars optimized-apdu4j.jar
-dontobfuscate
-dontoptimize

-keepattributes Exceptions,InnerClasses,Signature

# Keep all providers
-keep public class * extends java.security.Provider {*;}
-keep public class * extends javax.smartcardio.** {*;}

# Everything about RemoteTerminal is kept
-keep public class apdu4j.remote.RemoteTerminal {*;}
-keep public class apdu4j.remote.RemoteTerminalThread {*;}

# We use reflection here.
-keep public class apdu4j.remote.TestServer {*;}
-keep public class apdu4j.remote.SocketTransport {*;}
-keep public class apdu4j.LoggingCardTerminal {*;}
-keep public class apdu4j.HexUtils {*;}
-keep public class apdu4j.TerminalManager {
    public <methods>;
}
-keep public class apdu4j.ISO7816 {
    public <fields>;
}
# Command line utility
-keep public class apdu4j.SCTool {
    public static void main(java.lang.String[]);
}
# For enum-s (why this is not default?)
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-printseeds
-dontnote
