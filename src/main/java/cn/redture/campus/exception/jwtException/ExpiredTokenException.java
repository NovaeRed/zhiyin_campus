package cn.redture.campus.exception.jwtException;

import cn.redture.campus.constants.ErrorCodes;
import cn.redture.campus.exception.BaseException;
import org.springframework.http.HttpStatus;

/**
 * Token过期的异常
 */
public class ExpiredTokenException extends BaseException {
    public ExpiredTokenException(String message) {
        super(HttpStatus.UNAUTHORIZED, message, ErrorCodes.TOKEN_EXPIRED);
    }

    public ExpiredTokenException(String message, String errorCode) {
        super(HttpStatus.UNAUTHORIZED, message, errorCode);
    }
}

