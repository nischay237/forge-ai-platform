package com.forge.platform.common;

import org.springframework.http.HttpStatus;

public class ForgeException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ForgeException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
