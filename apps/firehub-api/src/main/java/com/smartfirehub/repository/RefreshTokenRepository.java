package com.smartfirehub.repository;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Repository
public class RefreshTokenRepository {

    private static final org.jooq.Table<?> REFRESH_TOKEN = table("refresh_token");
    private static final org.jooq.Field<Long> USER_ID = field("user_id", Long.class);
    private static final org.jooq.Field<String> TOKEN_HASH = field("token_hash", String.class);
    private static final org.jooq.Field<LocalDateTime> EXPIRES_AT = field("expires_at", LocalDateTime.class);
    private static final org.jooq.Field<Boolean> REVOKED = field("revoked", Boolean.class);

    private final DSLContext dsl;

    public RefreshTokenRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void save(Long userId, String tokenHash, LocalDateTime expiresAt) {
        dsl.insertInto(REFRESH_TOKEN)
                .set(USER_ID, userId)
                .set(TOKEN_HASH, tokenHash)
                .set(EXPIRES_AT, expiresAt)
                .execute();
    }

    public boolean existsValidToken(String tokenHash) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(REFRESH_TOKEN)
                        .where(TOKEN_HASH.eq(tokenHash))
                        .and(REVOKED.eq(false))
                        .and(EXPIRES_AT.gt(LocalDateTime.now()))
        );
    }

    public void revokeByTokenHash(String tokenHash) {
        dsl.update(REFRESH_TOKEN)
                .set(REVOKED, true)
                .where(TOKEN_HASH.eq(tokenHash))
                .execute();
    }

    public void revokeAllByUserId(Long userId) {
        dsl.update(REFRESH_TOKEN)
                .set(REVOKED, true)
                .where(USER_ID.eq(userId))
                .and(REVOKED.eq(false))
                .execute();
    }
}
