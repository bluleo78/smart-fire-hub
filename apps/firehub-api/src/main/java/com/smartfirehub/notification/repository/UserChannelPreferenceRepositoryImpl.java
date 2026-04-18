package com.smartfirehub.notification.repository;

import static com.smartfirehub.jooq.Tables.USER_CHANNEL_PREFERENCE;

import com.smartfirehub.notification.ChannelType;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * jOOQ 기반 사용자 채널 preference 구현.
 *
 * <p>isEnabled 정책: 테이블에 row가 없으면 기본값 true로 간주 (opt-in 없이도 정상 발송).
 * CHAT 채널은 DB CHECK 제약으로 disable 불가능하므로 항상 true 반환.
 */
@Repository
class UserChannelPreferenceRepositoryImpl implements UserChannelPreferenceRepository {

    private final DSLContext dsl;

    UserChannelPreferenceRepositoryImpl(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean isEnabled(long userId, ChannelType channelType) {
        // CHAT은 안전망 — DB 조회 생략, 항상 true
        if (channelType == ChannelType.CHAT) return true;

        Boolean enabled = dsl
                .select(USER_CHANNEL_PREFERENCE.ENABLED)
                .from(USER_CHANNEL_PREFERENCE)
                .where(USER_CHANNEL_PREFERENCE.USER_ID.eq(userId))
                .and(USER_CHANNEL_PREFERENCE.CHANNEL_TYPE.eq(channelType.name()))
                .fetchOne(USER_CHANNEL_PREFERENCE.ENABLED);

        // row 없으면 기본값 true (사용자가 명시적 off를 한 적이 없으면 허용)
        return enabled == null || enabled;
    }
}
