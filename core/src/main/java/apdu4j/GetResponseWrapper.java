package apdu4j;

import java.io.IOException;

public class GetResponseWrapper implements BIBO {
    BIBO wrapped;

    public static GetResponseWrapper wrap(BIBO bibo) {
        return new GetResponseWrapper(bibo);
    }

    public GetResponseWrapper(BIBO bibo) {
        this.wrapped = bibo;
    }

    @Override
    public byte[] transceive(byte[] command) throws IOException {
        byte[] response = new byte[0];
        // No open loops
        for (int i = 0; i < 32; i++) {
            byte[] r = wrapped.transceive(command);
            ResponseAPDU res = new ResponseAPDU(r);
            response = concatenate(response, res.getData());
            if (res.getSW1() == 0x61) {
                // XXX: dependence on CommandAPDU for 256
                command = new CommandAPDU(command[0], 0xC0, 0x00, 0x00, res.getSW2() == 0x00 ? 256 : res.getSW2()).getBytes();
            } else {
                response = concatenate(response, new byte[]{(byte) res.getSW1(), (byte) res.getSW2()});
                break;
            }
        }
        return response;
    }

    static byte[] concatenate(byte[]... args) {
        int length = 0, pos = 0;
        for (byte[] arg : args) {
            length += arg.length;
        }
        byte[] result = new byte[length];
        for (byte[] arg : args) {
            System.arraycopy(arg, 0, result, pos, arg.length);
            pos += arg.length;
        }
        return result;
    }
}
