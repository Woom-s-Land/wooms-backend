package com.ee06.wooms.global.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Component
public class JWTUtil {
    private final SecretKey secretKey;
    private final Long accessTokenValidityInMilliseconds;
    private final Long refreshTokenValidityInMilliseconds;

    public JWTUtil(@Value("${spring.jwt.secret}") String secret,
                   @Value("${spring.jwt.access-token-validity-in-seconds}") Long accessTokenValidityInMilliseconds,
                   @Value("${spring.jwt.refresh-token-validity-in-seconds}") Long refreshTokenValidityInMilliseconds
    ) {
        this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), Jwts.SIG.HS256.key().build().getAlgorithm());
        this.accessTokenValidityInMilliseconds = accessTokenValidityInMilliseconds * 1000;
        this.refreshTokenValidityInMilliseconds = refreshTokenValidityInMilliseconds * 1000;
    }

    public String getSubject(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build().parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public String getChannelUuid(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("channel-uuid", String.class);
    }

    public String getUuid(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("uuid", String.class);
    }

    public String getNickname(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("nickname", String.class);
    }

    public String getCostume(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("costume", String.class);
    }

    public long getExpiresAt(String token) {
        return Jwts.parser().verifyWith(secretKey).build()
                .parseSignedClaims(token).getPayload().getExpiration().getTime();
    }

    public String generateAccessToken(String uuid, String nickname, String costume, String channelUuid) {
        return Jwts.builder()
                .subject("access-token")
                .claim("uuid", uuid)
                .claim("channel-uuid", channelUuid)
                .claim("nickname", nickname)
                .claim("costume", costume)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + accessTokenValidityInMilliseconds))
                .signWith(secretKey)
                .compact();
    }

    public String generateRefreshToken(String uuid) {
        return Jwts.builder()
                .subject("refresh-token")
                .claim("uuid", uuid)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + refreshTokenValidityInMilliseconds))
                .signWith(secretKey)
                .compact();
    }

    public Boolean validateToken(String token) {
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token) != null;
    }
}
