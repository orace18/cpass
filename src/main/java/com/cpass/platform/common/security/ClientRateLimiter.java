package com.cpass.platform.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class ClientRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ClientRateLimiter.class);
    private static final DateTimeFormatter WINDOW_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private final StringRedisTemplate redisTemplate;

    public ClientRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isAllowed(UUID clientId, int limitPerMinute) {
        try {
            String windowKey = "auth:rl:" + clientId + ":" + LocalDateTime.now().format(WINDOW_FORMAT);
            Long count = redisTemplate.opsForValue().increment(windowKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(windowKey, Duration.ofSeconds(120));
            }
            return count != null && count <= limitPerMinute;
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable, rate limiting disabled: {}", e.getMessage());
            return true;
        }
    }
}
