-injars build
#-injars lib/bcprov-jdk15on-151.jar(!META-INF/*)
-injars lib/jopt-simple-4.8.jar(!META-INF/*)
# JNA is library because we package everything back in
-libraryjars lib/jnasmartcardio.jar
-libraryjars  <java.home>/lib/rt.jar
-libraryjars  <java.home>/lib/jce.jar
-outjars optimized-apdu4j.jar
-dontobfuscate
-dontoptimize
-keep public class apdu4j.APDUReplayProvider {
    public <methods>;
}
-keep public class apdu4j.LoggingCardTerminal {
    public <methods>;
}
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
