package apdu4j;

import java.io.IOException;

public class TagRemovedException extends IOException {

    private static final long serialVersionUID = 3318833899994140361L;

    public TagRemovedException(String message) {
        super(message);
    }

    public TagRemovedException(String message, Throwable e) {
        super(message, e);
    }
}
