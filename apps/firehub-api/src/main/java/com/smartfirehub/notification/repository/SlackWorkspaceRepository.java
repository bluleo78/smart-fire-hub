package com.smartfirehub.notification.repository;

import java.util.Optional;

/** Slack 워크스페이스(봇 토큰) 조회. Stage 2/3에서 OAuth 설치 로직 확장. */
public interface SlackWorkspaceRepository {
  Optional<SlackWorkspace> findByTeamId(String teamId);

  /**
   * 워크스페이스 ID로 단건 조회.
   *
   * @param id slack_workspace.id (PK)
   * @return revoked 여부 무관하게 반환 (호출 측에서 판단)
   */
  Optional<SlackWorkspace> findById(long id);

  /**
   * OAuth 설치 완료 시 워크스페이스 upsert.
   *
   * <p>team_id 충돌 시 team_name, bot_user_id, bot_token_enc, installed_by_user_id, revoked_at을 갱신하여
   * 재설치(re-install) 흐름을 지원한다.
   *
   * @param teamId Slack 팀(워크스페이스) ID
   * @param teamName 팀 이름
   * @param botUserId 봇 사용자 ID
   * @param botTokenEnc AES-256-GCM으로 암호화된 봇 토큰
   * @param installedByUserId 설치한 관리자 사용자 ID
   * @return 생성 또는 갱신된 레코드의 PK id
   */
  long upsertFromOAuth(
      String teamId, String teamName, String botUserId, String botTokenEnc, Long installedByUserId);

  /**
   * 워크스페이스 취소(revoke).
   *
   * <p>V51 마이그레이션에는 status 컬럼 없이 revoked_at 타임스탬프 방식으로 관리하므로 revoked_at=NOW() 설정.
   *
   * @param teamId revoke할 팀 ID
   */
  void revoke(String teamId);

  record SlackWorkspace(
      long id,
      String teamId,
      String teamName,
      String botUserId,
      String botTokenEnc,
      String signingSecretEnc,
      String previousSigningSecretEnc,
      java.time.Instant previousSigningSecretExpiresAt,
      Long installedByUserId) {}
}
