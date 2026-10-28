package cn.redture.campus.service;

import cn.redture.campus.exception.BaseException;
import cn.redture.campus.mapper.UserMapper;
import cn.redture.campus.pojo.entity.User;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AccountAccessService {
    private final UserMapper users;

    public AccountAccessService(UserMapper users) {
        this.users = users;
    }

    /**
     * 实时读取账号并校验其存在、未删除且已启用。
     *
     * @param id 待检查的用户标识
     * @return 当前仍可用的用户实体及实时角色信息
     */
    public User requireActive(Long id) {
        User user = id == null ? null : users.selectById(id);
        if (user == null || user.getDeletedAt() != null) {
            throw new BaseException(
                HttpStatus.UNAUTHORIZED, "账号不存在或已删除，请重新登录", "ACCOUNT_UNAVAILABLE");
        }
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new BaseException(HttpStatus.FORBIDDEN, "账号已停用", "ACCOUNT_DISABLED");
        }
        return user;
    }
}
