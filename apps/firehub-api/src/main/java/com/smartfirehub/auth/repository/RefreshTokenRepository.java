package com.smartfirehub.auth.repository;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

import static com.smartfirehub.jooq.Tables.*;

@Repository
public class RefreshTokenRepository {

    private final DSLContext dsl;

    public RefreshTokenRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void save(Long userId, String tokenHash, LocalDateTime expiresAt) {
        dsl.insertInto(REFRESH_TOKEN)
                .set(REFRESH_TOKEN.USER_ID, userId)
                .set(REFRESH_TOKEN.TOKEN_HASH, tokenHash)
                .set(REFRESH_TOKEN.EXPIRES_AT, expiresAt)
                .execute();
    }

    public boolean existsValidToken(String tokenHash) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(REFRESH_TOKEN)
                        .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
                        .and(REFRESH_TOKEN.REVOKED.eq(false))
                        .and(REFRESH_TOKEN.EXPIRES_AT.gt(LocalDateTime.now()))
        );
    }

    public void revokeByTokenHash(String tokenHash) {
        dsl.update(REFRESH_TOKEN)
                .set(REFRESH_TOKEN.REVOKED, true)
                .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
                .execute();
    }

    public void revokeAllByUserId(Long userId) {
        dsl.update(REFRESH_TOKEN)
                .set(REFRESH_TOKEN.REVOKED, true)
                .where(REFRESH_TOKEN.USER_ID.eq(userId))
                .and(REFRESH_TOKEN.REVOKED.eq(false))
                .execute();
    }
}
