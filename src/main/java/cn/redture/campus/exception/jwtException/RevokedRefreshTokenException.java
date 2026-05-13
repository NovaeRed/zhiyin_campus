package cn.redture.campus.exception.jwtException;

import cn.redture.campus.constants.ErrorCodes;
import cn.redture.campus.exception.BaseException;
import org.springframework.http.HttpStatus;

/**
 * 刷新令牌已被撤销（登出、改密码、封号等）。
 */
public class RevokedRefreshTokenException extends BaseException {

    public RevokedRefreshTokenException(String message) {
        super(HttpStatus.UNAUTHORIZED, message, ErrorCodes.REFRESH_TOKEN_REVOKED);
    }
}

