package cn.redture.campus.exception.businessException;

import cn.redture.campus.constants.ErrorCodes;
import cn.redture.campus.exception.BaseException;
import org.springframework.http.HttpStatus;

public class AccessDeniedException extends BaseException {

    public AccessDeniedException(String message) {
        super(HttpStatus.FORBIDDEN, message, ErrorCodes.ACCESS_DENIED);
    }
}
