package com.ee06.wooms.global.exception;

import com.ee06.wooms.global.common.CommonResponse;
import com.ee06.wooms.global.util.ErrorCodeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Objects;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidationExceptions(MethodArgumentNotValidException ex) {
        BindingResult result = ex.getBindingResult();
        return ErrorCodeUtils.build(HttpStatus.BAD_REQUEST, Objects.requireNonNull(result.getFieldError()).getDefaultMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> handleMaxSizeException(MaxUploadSizeExceededException ex) {
        // 413 Payload Too Large
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body("파일 크기가 50MB를 초과했습니다. 더 작은 파일을 업로드해주세요.");
    }
}
