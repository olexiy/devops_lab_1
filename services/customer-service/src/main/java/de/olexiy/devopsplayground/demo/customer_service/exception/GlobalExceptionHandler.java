package de.olexiy.devopsplayground.demo.customer_service.exception;

import com.bank.customer.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomerNotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(CustomerNotFoundException ex, HttpServletRequest req) {
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(CustomerEmailConflictException.class)
    ResponseEntity<ErrorResponse> handleConflict(CustomerEmailConflictException ex, HttpServletRequest req) {
        return buildError(HttpStatus.CONFLICT, ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return buildError(HttpStatus.BAD_REQUEST, message, req.getRequestURI());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> handleIllegalArg(IllegalArgumentException ex, HttpServletRequest req) {
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), req.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> buildError(HttpStatus status, String message, String path) {
        var body = new ErrorResponse()
                .timestamp(OffsetDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(path);
        return ResponseEntity.status(status).body(body);
    }
}
