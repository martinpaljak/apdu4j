-injars target/apdu4j.jar(!com/sun/jna/sunos**,!com/sun/jna/w32ce-arm)
-libraryjars <java.home>/lib/rt.jar

#-keep public class apdu4j.SCTool {
#    public static void main(java.lang.String[]);
#}

-keep class apdu4j.** { *; }

-keep public class * extends java.security.Provider {*;}
-keep public class * extends javax.smartcardio.** {*;}

-keep class com.sun.jna.** { *; }
-keep class jnasmartcardio.** { *; }

-dontobfuscate
-dontnote !apdu4j.**
-dontwarn !apcu4j.**

-printseeds seeds.txt

-outjars apdu4j.jar