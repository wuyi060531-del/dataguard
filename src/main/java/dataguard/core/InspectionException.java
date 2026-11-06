package dataguard.core;

/** A file-level failure; record-level quality problems are returned in the report. */
public class InspectionException extends RuntimeException {
    private final String code;
    private final Long lineNumber;

    public InspectionException(String code, String message) {
        this(code, message, null, null);
    }

    public InspectionException(String code, String message, Long lineNumber, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.lineNumber = lineNumber;
    }

    public String getCode() {
        return code;
    }

    public Long getLineNumber() {
        return lineNumber;
    }
}
