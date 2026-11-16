package com.wallet.common.exception;

import com.wallet.common.ApiResponse;
import com.wallet.common.ErrorCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();

        return ResponseEntity
            .status(errorCode.getStatus())
            .body(ApiResponse.error(errorCode));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String errorMessage = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();

        ErrorCode errorCode = ErrorCode.INPUT_INVALID;

        return ResponseEntity
            .status(errorCode.getStatus())
            .body(ApiResponse.error(errorCode.getCode(), errorMessage));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException e) {
        String message = e.getReason() == null ? "요청 처리에 실패했습니다." : e.getReason();
        return ResponseEntity.status(e.getStatusCode())
                .body(ApiResponse.error("REQUEST_FAILED", message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        return ResponseEntity.status(ErrorCode.INPUT_INVALID.getStatus())
                .body(ApiResponse.error(ErrorCode.INPUT_INVALID.getCode(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(
        HttpMessageNotReadableException e
    ) {
        return errorResponse(ErrorCode.INPUT_INVALID);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException e) {
        return errorResponse(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpRequestMethodNotSupportedException(
        HttpRequestMethodNotSupportedException e
    ) {
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .headers(e.getHeaders())
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception e) {
        log.error("처리되지 않은 서버 오류", e);
        return ResponseEntity.status(ErrorCode.SERVER_INTERNAL_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.SERVER_INTERNAL_ERROR));
    }

    private ResponseEntity<ApiResponse<Void>> errorResponse(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.error(errorCode));
    }
}
