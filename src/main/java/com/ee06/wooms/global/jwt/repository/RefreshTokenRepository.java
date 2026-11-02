package com.ee06.wooms.global.jwt.repository;

import com.ee06.wooms.global.jwt.dto.RefreshToken;

import java.util.Optional;

/**
 * Refresh token store. Previously backed by Spring Data Redis (CrudRepository);
 * now a plain interface so it can be implemented in-memory for local dev without
 * a running Redis. The id is the refresh token string itself (RefreshToken#refreshToken).
 */
public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findById(String refreshToken);

    void deleteById(String refreshToken);

    void deleteByRefreshToken(String refreshToken);
}
