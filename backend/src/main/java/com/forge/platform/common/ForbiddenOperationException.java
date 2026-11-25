package com.forge.platform.common;

import org.springframework.http.HttpStatus;

public class ForbiddenOperationException extends ForgeException {
    public ForbiddenOperationException(String message) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
}
