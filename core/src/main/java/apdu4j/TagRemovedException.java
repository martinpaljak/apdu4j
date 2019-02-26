package apdu4j;

import java.io.IOException;

public class TagRemovedException extends IOException {
    public TagRemovedException(String message) {
        super(message);
    }

    public TagRemovedException(String message, Throwable e) {
        super(message, e);
    }
}
