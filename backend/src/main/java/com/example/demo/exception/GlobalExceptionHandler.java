package com.example.demo.exception;

import com.example.demo.common.ApiErrorCode;
import com.example.demo.common.Result;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex) {
        return ResponseEntity
            .status(toHttpStatus(ex.getErrorCode()))
            .body(Result.error(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(Result.error(ApiErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(this::formatFieldError)
            .collect(Collectors.joining("; "));
        return badRequest(message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        return badRequest(ex.getReason() != null ? ex.getReason() : "参数校验失败");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations()
            .stream()
            .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
            .collect(Collectors.joining("; "));
        return badRequest(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        return badRequest("请求体格式错误");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingServletRequestParameter(MissingServletRequestParameterException ex) {
        return badRequest("缺少参数: " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String name = ex.getName() != null ? ex.getName() : "参数";
        return badRequest(name + " 格式不正确");
    }

    @ExceptionHandler({
        NoResourceFoundException.class,
        NoHandlerFoundException.class
    })
    public ResponseEntity<Result<Void>> handleNotFound(Exception ex) {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(Result.error(ApiErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity
            .status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(Result.error(ApiErrorCode.BAD_REQUEST, "不支持的请求方法: " + ex.getMethod()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Result.error(ApiErrorCode.INTERNAL_SERVER_ERROR));
    }

    private ResponseEntity<Result<Void>> badRequest(String message) {
        return ResponseEntity
            .badRequest()
            .body(Result.error(ApiErrorCode.BAD_REQUEST, message));
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }

    private HttpStatus toHttpStatus(ApiErrorCode errorCode) {
        return switch (errorCode) {
            case SUCCESS, SAFETY_STOCK_EXCEEDED, FORCE_DELETE_LOCATION_OK -> HttpStatus.OK;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, RESOURCE_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STOCK_NOT_ENOUGH, STOCK_DEDUCT_CONFLICT, ORDER_STATUS_INVALID, PAYMENT_TIMEOUT,
                 LOCATION_HAS_STOCK, FORCE_CLOSE_NOT_ALLOWED, RETURN_STATUS_INVALID,
                 BATCH_DATE_INVALID, RETURN_DUPLICATE, STOCK_RESERVE_FAILED, RESERVATION_CONFLICT,
                 ORDER_STATE_INVALID, IDEMPOTENCY_CONFLICT, REFUND_AMOUNT_EXCEEDED,
                 REFUND_TIME_WINDOW_EXCEEDED -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
