package com.gupify.exception;

public class NvidiaRateLimitException extends RuntimeException {
    public NvidiaRateLimitException(String message) {
        super(message);
    }
}