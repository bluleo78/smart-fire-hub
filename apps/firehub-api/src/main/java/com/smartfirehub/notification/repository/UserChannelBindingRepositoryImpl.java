package com.smartfirehub.notification.repository;

import static com.smartfirehub.jooq.Tables.USER_CHANNEL_BINDING;

import com.smartfirehub.notification.ChannelType;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * jOOQ 기반 user_channel_binding 조회 구현.
 *
 * <p>findActive는 status=ACTIVE인 binding을 1건 반환 (UNIQUE는 (user_id, channel_type, workspace_id)
 * 조합이므로 한 사용자의 SLACK binding이 여러 workspace에 걸쳐 있을 수 있으나 본 메서드는
 * 첫 번째 ACTIVE 행 사용. 워크스페이스별 분리가 필요하면 별도 메서드로 확장).
 */
@Repository
class UserChannelBindingRepositoryImpl implements UserChannelBindingRepository {

    private final DSLContext dsl;

    UserChannelBindingRepositoryImpl(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<UserChannelBinding> findActive(long userId, ChannelType channelType) {
        return dsl.selectFrom(USER_CHANNEL_BINDING)
                .where(USER_CHANNEL_BINDING.USER_ID.eq(userId))
                .and(USER_CHANNEL_BINDING.CHANNEL_TYPE.eq(channelType.name()))
                .and(USER_CHANNEL_BINDING.STATUS.eq("ACTIVE"))
                .orderBy(USER_CHANNEL_BINDING.CREATED_AT.desc())
                .limit(1)
                .fetchOptional()
                .map(r -> new UserChannelBinding(
                        r.getId(),
                        r.getUserId(),
                        ChannelType.valueOf(r.getChannelType()),
                        r.getWorkspaceId(),
                        r.getExternalUserId(),
                        r.getDisplayAddress(),
                        r.getAccessTokenEnc(),
                        r.getRefreshTokenEnc(),
                        r.getTokenExpiresAt() == null ? null : r.getTokenExpiresAt().toInstant(),
                        r.getStatus(),
                        r.getLastVerifiedAt() == null ? null : r.getLastVerifiedAt().toInstant(),
                        r.getCreatedAt().toInstant(),
                        r.getUpdatedAt().toInstant()
                ));
    }
}
