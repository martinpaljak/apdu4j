package apdu4j;

import java.io.IOException;

public class RetryWithRightLengthWrapper implements BIBO {
    BIBO wrapped;

    public static RetryWithRightLengthWrapper wrap(BIBO bibo) {
        return new RetryWithRightLengthWrapper(bibo);
    }

    private RetryWithRightLengthWrapper(BIBO bibo) {
        this.wrapped = bibo;
    }

    @Override
    public byte[] transceive(byte[] command) throws IOException {
        byte[] r = wrapped.transceive(command);
        ResponseAPDU res = new ResponseAPDU(r);
        if (res.getSW1() == 0x6C) {
            r = wrapped.transceive(new CommandAPDU(command[0], command[1], command[2], command[3], res.getSW2()).getBytes());
        }
        return r;
    }
}
