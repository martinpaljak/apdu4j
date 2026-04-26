// SPDX-FileCopyrightText: 2019 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

// Retries with correct Le when card responds with SW1=0x6C (wrong length)
public final class RetryWithRightLengthWrapper implements BIBO {
    private final BIBO wrapped;

    public static RetryWithRightLengthWrapper wrap(BIBO bibo) {
        return new RetryWithRightLengthWrapper(bibo);
    }

    public RetryWithRightLengthWrapper(BIBO bibo) {
        this.wrapped = bibo;
    }

    @Override
    public byte[] transceive(byte[] command) throws BIBOException {
        try {
            var response = new ResponseAPDU(wrapped.transceive(command));
            if (response.getSW1() == 0x6C) {
                var orig = new CommandAPDU(command);
                var data = orig.getNc() > 0 ? orig.getData() : null;
                var cmd = new CommandAPDU(orig.getCLA(), orig.getINS(), orig.getP1(), orig.getP2(), data, response.getSW2());
                return wrapped.transceive(cmd.getBytes());
            }
            return response.getBytes();
        } catch (IllegalArgumentException e) {
            throw new BIBOException("Invalid response APDU", e);
        }
    }

    @Override
    public void close() {
        wrapped.close();
    }
}
