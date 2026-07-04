package com.payflow.common;

import com.payflow.wallet.ConcurrentTransferException;
import com.payflow.wallet.InsufficientFundsException;
import com.payflow.wallet.WalletNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Single place for error -> HTTP mapping. Controllers stay clean;
 * clients get a consistent error shape: {"error": code, "message": detail}.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<?> insufficientFunds(InsufficientFundsException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient_funds", e.getMessage());
    }

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<?> walletNotFound(WalletNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "wallet_not_found", e.getMessage());
    }

    @ExceptionHandler(ConcurrentTransferException.class)
    public ResponseEntity<?> conflict(ConcurrentTransferException e) {
        return error(HttpStatus.CONFLICT, "concurrent_update", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<?> missingHeader(MissingRequestHeaderException e) {
        return error(HttpStatus.BAD_REQUEST, "missing_header", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new HashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(Map.of("error", "validation_failed", "fields", fields));
    }

    private ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", code, "message", message));
    }
}
