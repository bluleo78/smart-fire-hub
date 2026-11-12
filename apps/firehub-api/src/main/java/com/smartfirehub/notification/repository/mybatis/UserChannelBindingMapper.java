package com.smartfirehub.notification.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.notification.repository.UserChannelBinding;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * user_channel_binding MyBatis 매퍼(WD-709). SQL 은 {@code
 * mybatis/notification/UserChannelBindingMapper.xml}. 테넌트는 정책(RLS)이 맡는다 — tenant_id 는 INSERT
 * 값·WHERE 술어로 쓰지 않는다(열 DEFAULT 가 채운다). 토큰은 암호문 그대로 통과시키고 로그에 남기지 않는다.
 */
@PlatformMapper
public interface UserChannelBindingMapper {

  /** status=ACTIVE 인 binding 중 가장 최근 1건 — 없으면 null. */
  UserChannelBinding selectActive(
      @Param("userId") long userId, @Param("channelType") String channelType);

  /** 사용자의 모든 binding(status 무관) — 채널 종류, 최근 생성 순. */
  List<UserChannelBinding> selectByUser(@Param("userId") long userId);

  /**
   * workspace_id 가 있는 binding 의 upsert(PG ON CONFLICT ON CONSTRAINT uk_user_channel, Tibero MERGE).
   * 충돌 시 토큰·만료·상태·검증 시각·updated_at 만 갱신한다.
   */
  int upsert(
      @Param("userId") long userId,
      @Param("channelType") String channelType,
      @Param("workspaceId") Long workspaceId,
      @Param("externalUserId") String externalUserId,
      @Param("displayAddress") String displayAddress,
      @Param("accessTokenEnc") String accessTokenEnc,
      @Param("refreshTokenEnc") String refreshTokenEnc,
      @Param("tokenExpiresAt") Instant tokenExpiresAt,
      @Param("status") String status,
      @Param("lastVerifiedAt") Instant lastVerifiedAt,
      @Param("createdAt") Instant createdAt,
      @Param("updatedAt") Instant updatedAt,
      @Param("now") OffsetDateTime now);

  /**
   * workspace_id 가 NULL 인 binding 의 삽입 — NULL 은 유일 제약에서 서로 다르므로 충돌 없이 매번 새 행이다(PG NULLS DISTINCT).
   */
  int insertWithoutWorkspace(
      @Param("userId") long userId,
      @Param("channelType") String channelType,
      @Param("externalUserId") String externalUserId,
      @Param("displayAddress") String displayAddress,
      @Param("accessTokenEnc") String accessTokenEnc,
      @Param("refreshTokenEnc") String refreshTokenEnc,
      @Param("tokenExpiresAt") Instant tokenExpiresAt,
      @Param("status") String status,
      @Param("lastVerifiedAt") Instant lastVerifiedAt,
      @Param("createdAt") Instant createdAt,
      @Param("updatedAt") Instant updatedAt);

  /** status=REVOKED, updated_at=now. workspaceId 가 null 이면 workspace_id IS NULL 행만. */
  int revoke(
      @Param("userId") long userId,
      @Param("channelType") String channelType,
      @Param("workspaceId") Long workspaceId,
      @Param("now") OffsetDateTime now);

  /** team_id 의 워크스페이스에 속한 ACTIVE SLACK binding 1건 — 없으면 null. */
  UserChannelBinding selectByExternalId(
      @Param("teamId") String teamId, @Param("externalUserId") String externalUserId);
}
