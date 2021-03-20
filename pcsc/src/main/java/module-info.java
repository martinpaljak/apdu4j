module apdu4j.pcsc {
    requires transitive apdu4j.core;
    requires apdu4j.jnasmartcardio;
    requires transitive java.smartcardio;
    requires org.slf4j;
    requires org.yaml.snakeyaml;
    requires com.google.auto.service;

    exports apdu4j.pcsc;
    exports apdu4j.pcsc.terminals;
    exports apdu4j.pcsc.providers;

    // Not really, only for tests, but this seems to be harmless. PC/SC is desktop-oriented
    requires java.desktop;
}