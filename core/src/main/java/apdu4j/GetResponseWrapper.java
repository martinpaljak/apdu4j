package apdu4j;

import java.io.IOException;

public class GetResponseWrapper extends APDUBIBO {

    public GetResponseWrapper(APDUBIBO bibo) {
        super(bibo);
    }

    @Override
    public ResponseAPDU transmit(CommandAPDU command) throws IOException {
        byte[] response = new byte[0];
        // No open loops
        for (int i = 0; i < 32; i++) {
            ResponseAPDU r = super.transmit(command);
            response = concatenate(response, r.getData());
            if (r.getSW1() == 0x61) {
                // XXX: dependence on CommandAPDU for 256
                command = new CommandAPDU(command.getCLA(), 0xC0, 0x00, 0x00, r.getSW2() == 0x00 ? 256 : r.getSW2());
            } else {
                response = concatenate(response, new byte[]{(byte) r.getSW1(), (byte) r.getSW2()});
                break;
            }
        }
        return new ResponseAPDU(response);
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
