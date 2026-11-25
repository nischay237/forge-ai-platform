package com.forge.platform.common;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends ForgeException {
    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }
}
