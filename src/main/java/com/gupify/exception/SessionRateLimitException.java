package com.gupify.exception;

public class SessionRateLimitException extends RuntimeException {
    public SessionRateLimitException(String message) {
        super(message);
    }
}