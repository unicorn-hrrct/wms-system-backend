package com.example.demo.common;

public class Result<T> {

    private final int code;
    private final String message;
    private final T data;
    private final long timestamp;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> Result<T> success(T data) {
        return of(ApiErrorCode.SUCCESS.getCode(), ApiErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> success(String message, T data) {
        return of(ApiErrorCode.SUCCESS.getCode(), message, data);
    }

    public static Result<Void> success() {
        return of(ApiErrorCode.SUCCESS.getCode(), ApiErrorCode.SUCCESS.getMessage(), null);
    }

    public static Result<Void> error(ApiErrorCode errorCode) {
        return of(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static Result<Void> error(ApiErrorCode errorCode, String message) {
        return of(errorCode.getCode(), message, null);
    }

    public static <T> Result<T> of(int code, String message, T data) {
        return new Result<>(code, message, data);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
