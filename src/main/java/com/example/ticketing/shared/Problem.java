package com.example.ticketing.shared;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class Problem {
    public static Failure fail(int status, String message) {
        return new Failure(status, message);
    }

    @ExceptionHandler(Failure.class)
    ResponseEntity<ProblemDetail> failure(Failure e) {
        return ResponseEntity.status(e.status)
                .body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(e.status), e.getMessage()));
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    ResponseEntity<ProblemDetail> invalid(Exception e) {
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request"));
    }

    @ExceptionHandler({
            TransientDataAccessException.class,
            org.springframework.transaction.CannotCreateTransactionException.class
    })
    ResponseEntity<ProblemDetail> busy(Exception e) {
        return ResponseEntity.status(503)
                .header("Retry-After", "1")
                .body(
                        ProblemDetail.forStatusAndDetail(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "Transaction busy; retry with the same idempotency key"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict(Exception e) {
        return ResponseEntity.status(409)
                .body(
                        ProblemDetail.forStatusAndDetail(
                                HttpStatus.CONFLICT, "Conflicting state or duplicate resource"));
    }

    public static class Failure extends RuntimeException {
        public final int status;

        public Failure(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
