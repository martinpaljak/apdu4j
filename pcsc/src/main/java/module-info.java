module apdu4j.pcsc {
    requires apdu4j.core;
    requires apdu4j.jnasmartcardio;
    requires org.slf4j;
    requires java.smartcardio;
    requires org.yaml.snakeyaml;
    requires com.google.auto.service;

    exports apdu4j.pcsc;
    exports apdu4j.pcsc.terminals;
    exports apdu4j.pcsc.providers;
}