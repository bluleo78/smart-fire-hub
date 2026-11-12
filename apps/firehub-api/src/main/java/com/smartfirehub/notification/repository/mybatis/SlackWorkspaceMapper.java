package com.smartfirehub.notification.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository.SlackWorkspace;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Param;

/**
 * slack_workspace MyBatis 매퍼(WD-709). SQL 은 {@code mybatis/notification/SlackWorkspaceMapper.xml}.
 * 테넌트는 정책(RLS)이 맡는다 — tenant_id 는 INSERT 값·WHERE 술어로 쓰지 않는다. 봇 토큰·서명 비밀은 암호문 그대로 통과시키며 로그에 남기지 않는다.
 */
@PlatformMapper
public interface SlackWorkspaceMapper {

  /** team_id 로 활성(revoked_at IS NULL) 워크스페이스 — 없으면 null. */
  SlackWorkspace selectActiveByTeamId(@Param("teamId") String teamId);

  /** id 로 워크스페이스(revoked 포함) — 없으면 null. */
  SlackWorkspace selectById(@Param("id") long id);

  /** 활성 워크스페이스 중 id 가 가장 큰 1건 — 없으면 null. */
  SlackWorkspace selectFirstActive();

  /**
   * OAuth 설치 upsert(PG ON CONFLICT (team_id), Tibero MERGE ON team_id). 충돌 시 이름·봇·토큰·설치자를 갱신하고
   * revoked_at 을 비우며 signing secret 은 건드리지 않는다.
   */
  int upsertFromOAuth(
      @Param("teamId") String teamId,
      @Param("teamName") String teamName,
      @Param("botUserId") String botUserId,
      @Param("botTokenEnc") String botTokenEnc,
      @Param("installedByUserId") Long installedByUserId);

  /** team_id 로 PK 를 다시 읽는다(upsert 뒤 — team_id 는 전역 유일). */
  Long selectIdByTeamId(@Param("teamId") String teamId);

  /** revoked_at 을 지정 시각으로 채운다. */
  int revoke(@Param("teamId") String teamId, @Param("revokedAt") OffsetDateTime revokedAt);
}
