module apdu4j.tool {
    uses apdu4j.core.SmartCardApp;
    requires java.smartcardio;
    requires apdu4j.core;
    requires apdu4j.pcsc;
    requires apdu4j.jnasmartcardio;
    requires org.slf4j;
    requires org.slf4j.simple;
    requires info.picocli;
    requires com.googlecode.lanterna;
    requires com.google.auto.service;
}