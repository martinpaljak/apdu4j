package apdu4j.tool.apps;

import apdu4j.core.*;
import com.google.auto.service.AutoService;

@AutoService({SmartCardApp.class})
public class UID implements SimpleSmartCardApp {
    @Override
    public int run(BIBO bibo, String[] args) {
        CommandAPDU cmd = new CommandAPDU("FFCA000000");
        ResponseAPDU resp = new ResponseAPDU(bibo.transceive(cmd.getBytes()));
        if (resp.getSW() == 0x9000)
            System.out.printf("UID: %s%n", HexUtils.bin2hex(resp.getData()));
        else
            System.err.printf("UID not supported by reader? SW=%04Xd", resp.getSW());
        bibo.close();
        return 0;
    }
}
