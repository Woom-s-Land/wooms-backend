package com.ee06.wooms.global.jwt.repository;

import com.ee06.wooms.global.jwt.dto.RefreshToken;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link RefreshTokenRepository} backed by a ConcurrentHashMap.
 * Replaces the Redis-backed store so the app runs without Redis.
 *
 * Note: tokens live only for the JVM lifetime and TTL is not enforced here.
 * For a dev/single-instance setup that's fine; a multi-instance prod deployment
 * would need a shared store (e.g. Redis) again.
 */
@Repository
public class InMemoryRefreshTokenRepository implements RefreshTokenRepository {

    private final Map<String, RefreshToken> store = new ConcurrentHashMap<>();

    @Override
    public RefreshToken save(RefreshToken token) {
        store.put(token.getRefreshToken(), token);
        return token;
    }

    @Override
    public Optional<RefreshToken> findById(String refreshToken) {
        return Optional.ofNullable(store.get(refreshToken));
    }

    @Override
    public void deleteById(String refreshToken) {
        store.remove(refreshToken);
    }

    @Override
    public void deleteByRefreshToken(String refreshToken) {
        store.remove(refreshToken);
    }
}
