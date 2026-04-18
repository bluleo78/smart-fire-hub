package com.smartfirehub.notification.repository;

import static com.smartfirehub.jooq.Tables.USER_CHANNEL_BINDING;

import com.smartfirehub.notification.ChannelType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * jOOQ 기반 user_channel_binding CRUD 구현.
 *
 * <p>findActive: status=ACTIVE인 binding을 1건 반환. upsert: ON CONFLICT ON CONSTRAINT uk_user_channel 시
 * 토큰·상태 갱신. findByUser: 사용자의 모든 binding 반환 (settings 화면용). revoke: status=REVOKED 업데이트.
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
        .map(this::toRecord);
  }

  @Override
  public void upsert(UserChannelBinding binding) {
    // ON CONFLICT ON CONSTRAINT uk_user_channel: workspace_id NULL 포함 UNIQUE 제약 (V52)
    // 충돌 시 토큰·상태·만료시각·updated_at 갱신
    dsl.insertInto(USER_CHANNEL_BINDING)
        .set(USER_CHANNEL_BINDING.USER_ID, binding.userId())
        .set(USER_CHANNEL_BINDING.CHANNEL_TYPE, binding.channelType().name())
        .set(USER_CHANNEL_BINDING.WORKSPACE_ID, binding.workspaceId())
        .set(USER_CHANNEL_BINDING.EXTERNAL_USER_ID, binding.externalUserId())
        .set(USER_CHANNEL_BINDING.DISPLAY_ADDRESS, binding.displayAddress())
        .set(USER_CHANNEL_BINDING.ACCESS_TOKEN_ENC, binding.accessTokenEnc())
        .set(USER_CHANNEL_BINDING.REFRESH_TOKEN_ENC, binding.refreshTokenEnc())
        .set(
            USER_CHANNEL_BINDING.TOKEN_EXPIRES_AT,
            binding.tokenExpiresAt() == null
                ? null
                : OffsetDateTime.ofInstant(binding.tokenExpiresAt(), java.time.ZoneOffset.UTC))
        .set(USER_CHANNEL_BINDING.STATUS, binding.status())
        .set(
            USER_CHANNEL_BINDING.LAST_VERIFIED_AT,
            binding.lastVerifiedAt() == null
                ? null
                : OffsetDateTime.ofInstant(binding.lastVerifiedAt(), java.time.ZoneOffset.UTC))
        .set(
            USER_CHANNEL_BINDING.CREATED_AT,
            OffsetDateTime.ofInstant(binding.createdAt(), java.time.ZoneOffset.UTC))
        .set(
            USER_CHANNEL_BINDING.UPDATED_AT,
            OffsetDateTime.ofInstant(binding.updatedAt(), java.time.ZoneOffset.UTC))
        .onConflictOnConstraint(org.jooq.impl.DSL.name("uk_user_channel"))
        .doUpdate()
        .set(USER_CHANNEL_BINDING.ACCESS_TOKEN_ENC, binding.accessTokenEnc())
        .set(USER_CHANNEL_BINDING.REFRESH_TOKEN_ENC, binding.refreshTokenEnc())
        .set(
            USER_CHANNEL_BINDING.TOKEN_EXPIRES_AT,
            binding.tokenExpiresAt() == null
                ? null
                : OffsetDateTime.ofInstant(binding.tokenExpiresAt(), java.time.ZoneOffset.UTC))
        .set(USER_CHANNEL_BINDING.STATUS, binding.status())
        .set(
            USER_CHANNEL_BINDING.LAST_VERIFIED_AT,
            binding.lastVerifiedAt() == null
                ? null
                : OffsetDateTime.ofInstant(binding.lastVerifiedAt(), java.time.ZoneOffset.UTC))
        .set(USER_CHANNEL_BINDING.UPDATED_AT, OffsetDateTime.now(java.time.ZoneOffset.UTC))
        .execute();
  }

  @Override
  public List<UserChannelBinding> findByUser(long userId) {
    return dsl.selectFrom(USER_CHANNEL_BINDING)
        .where(USER_CHANNEL_BINDING.USER_ID.eq(userId))
        .orderBy(USER_CHANNEL_BINDING.CHANNEL_TYPE, USER_CHANNEL_BINDING.CREATED_AT.desc())
        .fetch()
        .map(this::toRecord);
  }

  @Override
  public void revoke(long userId, ChannelType channelType, Long workspaceId) {
    // workspaceId null이면 workspace_id IS NULL 조건, 있으면 = 조건
    var condition =
        USER_CHANNEL_BINDING
            .USER_ID
            .eq(userId)
            .and(USER_CHANNEL_BINDING.CHANNEL_TYPE.eq(channelType.name()));
    condition =
        workspaceId == null
            ? condition.and(USER_CHANNEL_BINDING.WORKSPACE_ID.isNull())
            : condition.and(USER_CHANNEL_BINDING.WORKSPACE_ID.eq(workspaceId));

    dsl.update(USER_CHANNEL_BINDING)
        .set(USER_CHANNEL_BINDING.STATUS, "REVOKED")
        .set(USER_CHANNEL_BINDING.UPDATED_AT, OffsetDateTime.now(java.time.ZoneOffset.UTC))
        .where(condition)
        .execute();
  }

  /** jOOQ record → UserChannelBinding 도메인 객체 변환. */
  private UserChannelBinding toRecord(
      com.smartfirehub.jooq.tables.records.UserChannelBindingRecord r) {
    return new UserChannelBinding(
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
        r.getUpdatedAt().toInstant());
  }
}
