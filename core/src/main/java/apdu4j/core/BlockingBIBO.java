package apdu4j.core;

import java.util.concurrent.ExecutionException;

public class BlockingBIBO implements BIBO {
    private final AsynchronousBIBO wrapped;

    public BlockingBIBO(AsynchronousBIBO bibo) {
        wrapped = bibo;
    }

    @Override
    public byte[] transceive(byte[] bytes) throws BIBOException {
        try {
            return wrapped.transmit(bytes).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new BIBOException("Failed waiting for response: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        wrapped.close();
    }
}
