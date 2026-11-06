package dataguard.service;

/** An expected application failure with an HTTP-safe message and stable machine code. */
public class ServiceException extends RuntimeException {
    private final String code;
    private final int status;

    public ServiceException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public ServiceException(String code, int status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String getCode() { return code; }
    public int getStatus() { return status; }
}
