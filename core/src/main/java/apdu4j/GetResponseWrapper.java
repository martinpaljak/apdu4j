package apdu4j;

import java.io.IOException;

public class GetResponseWrapper extends APDUBIBO {
    // APDU constants
    private static final int ETSI_GET_MORE_DATA_SW1 = 0x9F;
    private static final int ETSI_PIN_BLOCKED_SW2 = 0x00;
    private static final int ETSI_PIN_NOT_VERIFIED_SW2 = 0x04;
    private static final int ISO7816_4_GET_MORE_DATA_SW1 = 0x61;
    private static final byte GET_MORE_DATA_INS = (byte) 0xC0;

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
            boolean getMoreDataEtsi = this.getMoreDataEtsiFromResponse(r.getSW1(), r.getSW2());
            if ((r.getSW1() == ISO7816_4_GET_MORE_DATA_SW1) || getMoreDataEtsi) {
                // XXX: dependence on CommandAPDU for 256
                command = new CommandAPDU(command.getCLA(), GET_MORE_DATA_INS, 0x00, 0x00,
                        r.getSW2() == 0x00 ? 256 : r.getSW2());
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

    private boolean getMoreDataEtsiFromResponse(int s1, int s2) {
        return (s1 == ETSI_GET_MORE_DATA_SW1) && !((s2 == ETSI_PIN_BLOCKED_SW2) || (s2 == ETSI_PIN_NOT_VERIFIED_SW2));
    }
}
