package cn.redture.campus.security.ratelimit.core;

import cn.redture.campus.security.ratelimit.EntryRateLimitProperties;

/**
 * 限流算法统一接口。
 */
public interface RateLimiterAlgorithm {

    RateLimitConsumeResult consume(EntryRateLimitProperties.Rule rule, String identity);
}
