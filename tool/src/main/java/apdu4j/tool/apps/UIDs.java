package apdu4j.tool.apps;

import apdu4j.core.*;
import com.google.auto.service.AutoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

@AutoService({SmartCardApp.class})
public class UIDs extends SmartCardAppFutures {
    private static final Logger logger = LoggerFactory.getLogger(UIDs.class);

    @Override
    public CompletableFuture<AppParameters> onStart(String[] argv) {
        System.out.println("Starting app, params: " + Arrays.toString(argv));
        AppParameters params = new AppParameters();
        params.put(AppParameters.TOUCH_REQUIRED_BOOLEAN, Boolean.TRUE.toString());
        params.put(AppParameters.MULTISESSION_BOOLEAN, Boolean.TRUE.toString());
        System.out.println("Present cards to reader to print their UID-s");
        return CompletableFuture.completedFuture(params);
    }

    @Override
    public void onCardPresent(AsynchronousBIBO transport, CardData props) {
        System.out.println("Card presented: " + props);
        CommandAPDU cmd = new CommandAPDU("FFCA000000");
        transport.transmit(cmd.getBytes()).thenAccept((response) -> {
            ResponseAPDU resp = new ResponseAPDU(response);
            if (resp.getSW() == 0x9000)
                System.out.printf("UID: %s%n", HexUtils.bin2hex(resp.getData()));
            else
                System.err.printf("UID not supported by reader? SW=%04Xd", resp.getSW());
            transport.close();
        });
    }

    @Override
    public void onError(Throwable e) {
        logger.error("We had error: {}", e.getMessage(), e);
        System.out.println("Error occured");
        e.printStackTrace();
    }

    @Override
    public void onCardRemoved() {
        logger.info("Card was removed");
        System.out.println("Card removed");
    }
}
