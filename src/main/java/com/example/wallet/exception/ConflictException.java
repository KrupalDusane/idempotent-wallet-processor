package com.example.wallet.exception;

/**
 * Thrown when a duplicate request cannot be safely answered (original still in
 * progress, or original failed). Mapped to HTTP 409 by the exception handler.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
