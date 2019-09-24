package apdu4j;

public class BIBOException extends RuntimeException {
    public static final long serialVersionUID = 6710240956038548175L;

    public BIBOException(String message) {
        super(message);
    }

    public BIBOException(String message, Throwable e) {
        super(message, e);
    }
}
