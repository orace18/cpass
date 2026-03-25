package com.cpass.platform.common.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class NonceStoreService {

    private final StringRedisTemplate redisTemplate;

    public NonceStoreService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean registerNonce(String apiKey, String nonce, long ttlSeconds) {
        String key = "auth:nonce:" + apiKey + ":" + nonce;
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(ttlSeconds));
        return Boolean.TRUE.equals(accepted);
    }
}
