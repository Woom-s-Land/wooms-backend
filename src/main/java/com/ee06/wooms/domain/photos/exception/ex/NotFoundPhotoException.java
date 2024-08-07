package com.ee06.wooms.domain.photos.exception.ex;

import com.ee06.wooms.global.exception.ErrorCode;
import lombok.Getter;

@Getter
public class NotFoundPhotoException extends RuntimeException {
    private final ErrorCode errorCode;
    public NotFoundPhotoException() {
        this.errorCode = ErrorCode.NOT_FOUND_PHOTO;
    }
}
