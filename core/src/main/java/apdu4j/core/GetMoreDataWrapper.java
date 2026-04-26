// SPDX-FileCopyrightText: 2019 Martin Paljak <martin@martinpaljak.net>
// SPDX-License-Identifier: MIT
package apdu4j.core;

// Chains GET RESPONSE commands on SW1=0x9F (ETSI TS 102.221 / GSM 11.11)
public final class GetMoreDataWrapper implements BIBO {
    private final BIBO wrapped;

    public static GetMoreDataWrapper wrap(BIBO bibo) {
        return new GetMoreDataWrapper(bibo);
    }

    public GetMoreDataWrapper(BIBO bibo) {
        this.wrapped = bibo;
    }

    @Override
    public byte[] transceive(byte[] command) throws BIBOException {
        return GetResponseWrapper.chainOnSw1(wrapped, command, 0x9F);
    }

    @Override
    public void close() {
        wrapped.close();
    }
}
