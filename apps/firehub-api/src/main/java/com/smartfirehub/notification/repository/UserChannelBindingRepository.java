package com.smartfirehub.notification.repository;

import com.smartfirehub.notification.ChannelType;
import java.util.List;
import java.util.Optional;

/** 사용자 채널 binding CRUD. */
public interface UserChannelBindingRepository {

  /** status=ACTIVE인 binding 1건 반환. */
  Optional<UserChannelBinding> findActive(long userId, ChannelType channelType);

  /**
   * binding upsert — (user_id, channel_type, workspace_id) 충돌 시 토큰·상태 갱신, 없으면 INSERT.
   *
   * <p>workspace_id=NULL인 KAKAO 바인딩에도 UNIQUE 제약이 적용된다 (V52 마이그레이션의 uk_user_channel 제약).
   */
  void upsert(UserChannelBinding binding);

  /** 사용자의 모든 binding 조회 — status 무관. settings 화면에서 연동 상태 표시용. */
  List<UserChannelBinding> findByUser(long userId);

  /**
   * binding 해제 — status=REVOKED, updated_at=now() 업데이트.
   *
   * @param workspaceId null이면 workspace_id IS NULL 조건으로 처리 (KAKAO 등)
   */
  void revoke(long userId, ChannelType channelType, Long workspaceId);

  /**
   * 팀 ID + 외부 사용자 ID로 활성 binding 조회 (Slack inbound 사용자 매핑용).
   *
   * <p>SLACK_WORKSPACE.team_id를 통해 워크스페이스를 특정하고, 해당 워크스페이스에 속한
   * ACTIVE 상태의 binding을 반환한다.
   *
   * @param teamId         Slack 워크스페이스 team_id
   * @param externalUserId Slack 사용자 ID (U로 시작하는 Slack user id)
   * @return 활성 binding, 없으면 Optional.empty()
   */
  Optional<UserChannelBinding> findByExternalId(String teamId, String externalUserId);
}
