package com.helpdesk.api.shared;

import org.springframework.http.HttpStatus;

/** Error de negocio con código estable para el cliente (formato Problem Details, RFC 9457). */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", what + " no encontrado");
    }
    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "SIN_PERMISO", "No tienes permiso para esta acción");
    }
    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
