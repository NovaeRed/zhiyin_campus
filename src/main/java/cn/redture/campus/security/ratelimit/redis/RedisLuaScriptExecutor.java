package cn.redture.campus.security.ratelimit.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Redis Lua 脚本执行器。
 */
@Component
public class RedisLuaScriptExecutor {

    private final StringRedisTemplate stringRedisTemplate;

    private final DefaultRedisScript<List> tokenBucketScript;

    public RedisLuaScriptExecutor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.tokenBucketScript = new DefaultRedisScript<>();
        this.tokenBucketScript.setLocation(new ClassPathResource("lua/entry_token_bucket.lua"));
        this.tokenBucketScript.setResultType(List.class);
    }

    /**
     * 执行单个桶键的Lua原子操作并规范化返回值；脚本结果缺失时返回保守的拒绝结果。
     *
     * @param key                 需要更新的Redis令牌桶键
     * @param nowMs               当前时间戳，单位毫秒
     * @param capacityScaled      放大后的桶容量
     * @param refillTokensScaled  每周期放大后的补充量
     * @param refillPeriodMs      补充周期，单位毫秒
     * @param requestTokensScaled 本次请求需要消耗的放大令牌量
     * @param ttlMs               桶状态过期时间，单位毫秒
     * @return 依次为是否放行、剩余完整令牌数、建议重试秒数的三项结果
     */
    public List<Long> executeTokenBucket(
        String key,
        long nowMs,
        long capacityScaled,
        long refillTokensScaled,
        long refillPeriodMs,
        long requestTokensScaled,
        long ttlMs) {
        List<?> rawResult =
            stringRedisTemplate.execute(
                tokenBucketScript,
                Collections.singletonList(key),
                String.valueOf(nowMs),
                String.valueOf(capacityScaled),
                String.valueOf(refillTokensScaled),
                String.valueOf(refillPeriodMs),
                String.valueOf(requestTokensScaled),
                String.valueOf(ttlMs));

        if (rawResult == null || rawResult.size() < 3) {
            return List.of(0L, 0L, 1L);
        }

        return List.of(
            toLong(rawResult.get(0)), toLong(rawResult.get(1)), toLong(rawResult.get(2)));
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
