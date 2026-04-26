// SPDX-FileCopyrightText: 2019 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

// Extension on top of BIBO, not unlike CardChannel, which allows
// to transmit APDU-s back and forth over BIBO. Drop-in replacement for
// code that currently uses javax.smartcardio *APDU, with a new import for *APDU
public final class APDUBIBO implements BIBO {
    private final BIBO bibo;

    public APDUBIBO(BIBO bibo) {
        this.bibo = bibo;
    }

    public ResponseAPDU transmit(CommandAPDU command) throws BIBOException {
        try {
            return new ResponseAPDU(bibo.transceive(command.getBytes()));
        } catch (IllegalArgumentException e) {
            throw new BIBOException("Invalid response APDU", e);
        }
    }

    @Override
    public byte[] transceive(byte[] bytes) throws BIBOException {
        return bibo.transceive(bytes);
    }

    @Override
    public void close() {
        bibo.close();
    }
}
