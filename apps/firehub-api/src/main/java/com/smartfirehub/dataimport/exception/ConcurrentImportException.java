package com.smartfirehub.dataimport.exception;

public class ConcurrentImportException extends RuntimeException {
    public ConcurrentImportException(String message) {
        super(message);
    }
}
