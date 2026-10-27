package cn.redture.campus.service.impl;

import cn.redture.campus.constants.RedisConstants;
import cn.redture.campus.exception.BaseException;
import cn.redture.campus.exception.businessException.InvalidInputException;
import cn.redture.campus.exception.jwtException.ExpiredRefreshTokenException;
import cn.redture.campus.exception.jwtException.InvalidRefreshTokenException;
import cn.redture.campus.exception.jwtException.InvalidTokenException;
import cn.redture.campus.exception.jwtException.RevokedRefreshTokenException;
import cn.redture.campus.mapper.UserMapper;
import cn.redture.campus.pojo.dto.LoginRequestDTO;
import cn.redture.campus.pojo.dto.RegisterRequestDTO;
import cn.redture.campus.pojo.dto.TokenResponseDTO;
import cn.redture.campus.pojo.entity.User;
import cn.redture.campus.service.AuthService;
import cn.redture.campus.util.JwtUtil;
import cn.redture.campus.util.RegexUtil;
import cn.redture.campus.util.TokenManagementUtil;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import jakarta.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private PasswordEncoder passwordEncoder;

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private TokenManagementUtil tokenManagementUtil;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserMapper userMapper;

    @Resource
    private cn.redture.campus.service.AccountAccessService accounts;

    @Override
    public TokenResponseDTO register(RegisterRequestDTO request) {
        if (request.getStudentNo() == null
            || request.getStudentNo().isBlank()
            || request.getStudentNo().length() > 32) {
            throw new InvalidInputException("学号长度应为1～32字符");
        }
        if (request.getUsername() == null
            || request.getUsername().isBlank()
            || request.getUsername().length() > 50) {
            throw new InvalidInputException("用户名长度应为1～50字符");
        }
        // 检查学号是否已存在
        Long count =
            userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                    .eq(User::getStudentNo, request.getStudentNo())
                    .isNull(User::getDeletedAt));
        if (count != null && count > 0) {
            throw new BaseException(HttpStatus.CONFLICT, "学号已存在", "STUDENT_NO_EXISTS");
        }

        if (request.getPassword() == null
            || request.getPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            > 72) {
            throw new InvalidInputException("密码不能为空且UTF-8编码长度不能超过72字节");
        }
        if (!RegexUtil.isStrongPassword(request.getPassword())) {
            throw new InvalidInputException("密码强度不足，密码应至少包含8位，且包含大小写字母、数字和特殊字符");
        }

        User user = new User();
        user.setStudentNo(request.getStudentNo());
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole("student");
        user.setIsActive(true);
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        try {
            userMapper.insert(user);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // 唯一约束同时防止并发注册冲突以及复用软删除账号的学号。
            throw new BaseException(HttpStatus.CONFLICT, "学号已存在", "STUDENT_NO_EXISTS");
        }

        return generateAccessAndRefreshToken(user);
    }

    @Override
    public TokenResponseDTO login(LoginRequestDTO request) {
        if (request.getStudentNo() == null
            || request.getStudentNo().isBlank()
            || request.getStudentNo().length() > 32
            || request.getPassword() == null
            || request.getPassword().isBlank()
            || request.getPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            > 72) {
            throw new InvalidInputException("请提供有效学号和密码");
        }
        User user =
            userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                    .eq(User::getStudentNo, request.getStudentNo())
                    .isNull(User::getDeletedAt));
        if (user == null) {
            throw new BaseException(HttpStatus.UNAUTHORIZED, "学号或密码错误", "BAD_CREDENTIALS");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BaseException(HttpStatus.UNAUTHORIZED, "学号或密码错误", "BAD_CREDENTIALS");
        }
        accounts.requireActive(user.getId());

        user.setLastLoginAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        userMapper.updateById(user);

        return generateAccessAndRefreshToken(user);
    }

    @Override
    public void logout(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return;
        }
        String accessToken = authorizationHeader.substring("Bearer ".length());

        try {
            // 1. 即使令牌过期，也尝试从中解析出 userId
            Long userId = jwtUtil.getUserIdFromExpiredToken(accessToken);

            // 2. 将当前 access token 加入黑名单
            tokenManagementUtil.addAccessTokenToBlacklist(accessToken);

            // 3. 从 Redis 中删除对应的 refresh token
            if (userId != null) {
                String key = RedisConstants.AUTH_REFRESH_TOKEN_KEY_PREFIX + userId;
                stringRedisTemplate.delete(key);
            }
        } catch (InvalidTokenException ignored) {
        }
    }

    @Override
    public TokenResponseDTO refreshToken(String expiredAccessToken, String refreshToken) {
        if (expiredAccessToken == null || refreshToken == null) {
            throw new InvalidRefreshTokenException("访问令牌和刷新令牌均不能为空");
        }

        // 1. 从过期的 access token 中解析 uid
        Long uidFromAccessToken;
        try {
            uidFromAccessToken = jwtUtil.getUserIdFromExpiredToken(expiredAccessToken);
        } catch (InvalidTokenException e) {
            throw new InvalidRefreshTokenException("无效的访问令牌");
        }

        // 2. 从 refresh token 中解析 uid 并校验
        Long uidFromRefreshToken;
        try {
            uidFromRefreshToken = jwtUtil.getUserIdFromRefreshToken(refreshToken);
        } catch (InvalidTokenException | ExpiredRefreshTokenException e) {
            throw new InvalidRefreshTokenException("无效或已过期的刷新令牌");
        }

        // 3. 校验两个 token 是否属于同一个用户
        if (!uidFromAccessToken.equals(uidFromRefreshToken)) {
            throw new InvalidRefreshTokenException("令牌不匹配");
        }
        User user = accounts.requireActive(uidFromRefreshToken);

        // 4. 从 Redis 中检查 refresh token 是否存在且匹配
        String key = RedisConstants.AUTH_REFRESH_TOKEN_KEY_PREFIX + uidFromRefreshToken;
        String tokenInRedis = stringRedisTemplate.opsForValue().get(key);

        if (tokenInRedis == null) {
            throw new ExpiredRefreshTokenException("会话已过期，请重新登录");
        }
        if (!tokenInRedis.equals(refreshToken)) {
            // 如果不匹配，可能意味着 refresh token 已被盗用并轮换。
            // 出于安全考虑，立即吊销该用户的所有会话。
            stringRedisTemplate.delete(key);
            throw new RevokedRefreshTokenException("会话已失效，请重新登录");
        }

        // 5. 将旧的、已过期的 access token 加入黑名单，防止重放
        tokenManagementUtil.addAccessTokenToBlacklist(expiredAccessToken);

        // 6. 验证通过，生成一对全新的 token
        return generateAccessAndRefreshToken(user);
    }

    /**
     * 生成一对新的访问令牌和刷新令牌
     *
     * @param user 用户实体
     * @return TokenResponseDTO 包含新的访问令牌和刷新令牌
     */
    private TokenResponseDTO generateAccessAndRefreshToken(User user) {
        // 生成 access token 和 refresh token
        String accessToken = tokenManagementUtil.generateAccessToken(user);
        String refreshToken = tokenManagementUtil.generateAndStoreRefreshToken(user.getId());
        return tokenManagementUtil.buildTokenResponse(accessToken, refreshToken);
    }
}
