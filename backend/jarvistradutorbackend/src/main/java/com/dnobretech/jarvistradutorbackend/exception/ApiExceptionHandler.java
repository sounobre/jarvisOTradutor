package com.dnobretech.jarvistradutorbackend.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    // 404
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(EntityNotFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ApiError(404, "Not Found", ex.getMessage(), req.getRequestURI(), Instant.now())
        );
    }

    // 400 - argumentos inválidos em geral
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex, HttpServletRequest req) {
        return ResponseEntity.badRequest().body(
                new ApiError(400, "Bad Request", ex.getMessage(), req.getRequestURI(), Instant.now())
        );
    }

    // 400 - validação do corpo com @Valid (@RequestBody)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationError> handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        var errs = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErr(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(
                new ValidationError(400, "Bad Request", errs, req.getRequestURI(), Instant.now())
        );
    }

    // 400 - validação de params/query/path (@Validated) e binding de objetos simples
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ValidationError> handleBindValidation(BindException ex, HttpServletRequest req) {
        var errs = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErr(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(
                new ValidationError(400, "Bad Request", errs, req.getRequestURI(), Instant.now())
        );
    }

    // 400 - violações programáticas (ex.: @Min, @NotNull em params)
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        return ResponseEntity.badRequest().body(
                new ApiError(400, "Bad Request", ex.getMessage(), req.getRequestURI(), Instant.now())
        );
    }

    // 400 - erros de integridade do banco (FK/UNIQUE etc.)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        String msg = (ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage());
        return ResponseEntity.badRequest().body(
                new ApiError(400, "Bad Request", msg, req.getRequestURI(), Instant.now())
        );
    }

    // 500 - fallback único
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Erro não tratado", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ApiError(500, "Internal Server Error", ex.getMessage(), req.getRequestURI(), Instant.now())
        );
    }
}
