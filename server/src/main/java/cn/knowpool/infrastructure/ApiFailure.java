package cn.knowpool.infrastructure;

import java.util.List;

public final class ApiFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final List<FieldError> fieldErrors;

    public ApiFailure(int status, String code, String message, String field) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = field == null ? List.of() : List.of(new FieldError(field, code, message));
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    public static ApiFailure invalid(String field, String message) {
        return new ApiFailure(400, "INVALID_REQUEST", message, field);
    }

    public record FieldError(String field, String code, String message) implements java.io.Serializable {}
}
