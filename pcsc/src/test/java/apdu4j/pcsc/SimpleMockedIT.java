package apdu4j.pcsc;

import apdu4j.core.HexUtils;
import apdu4j.core.SmartCardAppFutures;
import apdu4j.pcsc.providers.APDUReplayProvider;
import apdu4j.pcsc.terminals.SynthesizedCardTerminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import javax.smartcardio.*;
import java.security.NoSuchAlgorithmException;

public class SimpleMockedIT {
    static {
        // Set up slf4j simple in a way that pleases us
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "true");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true");
        // Default level
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss:SSS");
    }

    private static final Logger logger = LoggerFactory.getLogger(SimpleMockedIT.class);

    CardTerminal terminalMaker() {
        try {
            TerminalFactory factory = TerminalFactory.getInstance("PC/SC", SimpleMockedIT.class.getResourceAsStream("test.dump"), new APDUReplayProvider());
            TerminalManager manager = new TerminalManager(factory);
            return manager.getTerminal(APDUReplayProvider.READER_NAME);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testSimpleEmulatedTerminalApp() throws Exception {
        long start = System.currentTimeMillis();
        SampleApp app = new SampleApp();
        Thread t = new Thread(CardTerminalAppRunner.once(this::terminalMaker, app));
        t.start();
        t.join();
        long duration = System.currentTimeMillis() - start;
        logger.info("Test took {} ms", duration);
        Assert.assertTrue(duration < 700, "Execution took more than 700ms");
    }


    @Test
    public void testSynthesizedCardTerminal() throws Exception {
        long start = System.currentTimeMillis();

        SmartCardAppFutures pipe = new SmartCardAppFutures() {
            @Override
            public String getName() {
                return "test";
            }
        };
        Thread t = new Thread(CardTerminalAppRunner.once(this::terminalMaker, pipe));
        t.start();

        // This is synchronous
        CardTerminal cardTerminal = new SynthesizedCardTerminal(pipe);
        Assert.assertEquals(cardTerminal.getName(), "CloudSmartCard emulated reader");
        Assert.assertTrue(cardTerminal.waitForCardPresent(1000)); // Should be enough
        Card c = cardTerminal.connect("*");
        Assert.assertEquals(c.getProtocol(), "T=1");
        Assert.assertEquals(c.getATR().getBytes(), HexUtils.hex2bin("3BF91300008131FE454A434F503234325232A3"));
        CardChannel bc = c.getBasicChannel();
        ResponseAPDU r = bc.transmit(new CommandAPDU(HexUtils.hex2bin("00A4040000")));
        Assert.assertEquals(r.getBytes(), HexUtils.hex2bin("6f648408a000000151000000a5589f6501ff9f6e06479120813b00734906072a864886fc6b01600b06092a864886fc6b020202630906072a864886fc6b03640b06092a864886fc6b040255650b06092b8510864864020103660c060a2b060104012a026e01029000"));
        c.disconnect(true);
        t.join();
        long duration = System.currentTimeMillis() - start;
        logger.info("Test took {} ms", duration);
        Assert.assertTrue(duration < 700, "Execution took more than 700ms");
    }
}
