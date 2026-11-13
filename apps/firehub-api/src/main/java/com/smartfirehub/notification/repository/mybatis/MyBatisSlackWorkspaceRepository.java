package com.smartfirehub.notification.repository.mybatis;

import com.smartfirehub.global.platformdb.PlatformDbErrors;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformRetries;
import com.smartfirehub.notification.repository.SlackTeamOwnedByAnotherTenantException;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * SlackWorkspaceRepository 의 MyBatis 구현(WD-709) — {@code JooqSlackWorkspaceRepository} 와 같은 의미.
 *
 * <p>클래스 단위 {@code @Transactional} 은 jOOQ 구현 그대로이며 지우면 안 된다 — 트랜잭션이 없으면 RLS 컨텍스트가 심기지 않아 조회가 조용히
 * 0행이 된다. 봇 토큰·서명 비밀은 암호문 그대로 통과시키고 로그에 남기지 않는다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisSlackWorkspaceRepository implements SlackWorkspaceRepository {

  /** team_id 전역 유일 키 이름(PG V51·Tibero V155 동일). 이 키의 중복만 「다른 테넌트 소유」로 본다. */
  static final String TEAM_ID_KEY = "slack_workspace_team_id_key";

  private final SlackWorkspaceMapper mapper;
  private final PlatformDbErrors platformDbErrors;

  public MyBatisSlackWorkspaceRepository(
      SlackWorkspaceMapper mapper, PlatformDbErrors platformDbErrors) {
    this.mapper = mapper;
    this.platformDbErrors = platformDbErrors;
  }

  @Override
  public Optional<SlackWorkspace> findByTeamId(String teamId) {
    return Optional.ofNullable(mapper.selectActiveByTeamId(teamId)).map(this::normalize);
  }

  @Override
  public Optional<SlackWorkspace> findById(long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(this::normalize);
  }

  @Override
  public Optional<SlackWorkspace> findFirstActive() {
    return Optional.ofNullable(mapper.selectFirstActive()).map(this::normalize);
  }

  /**
   * team_id 충돌 시 갱신, 없으면 삽입한 뒤 id 를 team_id 로 다시 읽는다(Tibero 에는 RETURNING 이 없다). 최초 설치의 signing
   * secret 은 빈 값(Tibero 는 NULL — 읽을 때 "" 로 돌린다)이고 재설치는 기존 값을 보존한다. Tibero MERGE 의 동시 첫 설치 패자는 중복 키로
   * 실패할 수 있어 한 번 다시 시도한다.
   *
   * <p>다른 테넌트가 이미 설치한 team: team_id 는 테넌트를 가로질러 유일한데, 다른 테넌트에 설치된 행은 RLS 로 보이지 않는다. 그래서 MERGE 가 그
   * 행을 「없음」으로 보고 삽입하려다 team_id 키에 걸린다. 같은 테넌트의 동시 설치는 위 재시도가 흡수하므로, 재시도 뒤에도 남은 <b>team_id 키</b> 중복만
   * {@link SlackTeamOwnedByAnotherTenantException} 으로 바꿔 올린다(서비스가 PG 의 RLS 거부와 같은 설치 거절로 처리,
   * WD-730). 다른 제약의 중복은 원래 {@code DuplicateKeyException} 그대로 올라간다. PG 는 같은 상황을 RLS 위반(42501)으로 내며 이
   * 신호를 내지 않는다.
   *
   * <p>남은 일(WD-730): Tibero FH_TENANT 가 workspace_id 를 돌려주도록 바꾸고 REQUIRES_NEW 를 걷는 것은 마이그레이션이 필요해
   * 별도다.
   */
  @Override
  public long upsertFromOAuth(
      String teamId,
      String teamName,
      String botUserId,
      String botTokenEnc,
      Long installedByUserId) {
    try {
      PlatformRetries.runOnceOnDuplicateKey(
          () ->
              mapper.upsertFromOAuth(teamId, teamName, botUserId, botTokenEnc, installedByUserId));
    } catch (DuplicateKeyException e) {
      if (platformDbErrors
          .violatedConstraint(e)
          .filter(TEAM_ID_KEY::equalsIgnoreCase)
          .isPresent()) {
        throw new SlackTeamOwnedByAnotherTenantException(teamId, e);
      }
      throw e;
    }
    Long id = mapper.selectIdByTeamId(teamId);
    // upsert 직후 같은 트랜잭션에서 다시 읽으므로 null 이면 안 된다 — 자동 언박싱 NPE 대신 원인을 밝힌다.
    if (id == null) {
      throw new IllegalStateException("슬랙 워크스페이스 upsert 직후 team_id 로 id 를 찾지 못했다");
    }
    return id;
  }

  @Override
  public void revoke(String teamId) {
    mapper.revoke(teamId, OffsetDateTime.now(ZoneOffset.UTC));
  }

  /**
   * signing_secret_enc 의 NULL 을 "" 로 돌린다(스펙 D3). PG 는 열이 NOT NULL 이라 NULL 이 올 수 없고, Tibero 는
   * ''=NULL 이라 최초 설치 행이 NULL 로 저장된다 — 이 열만의 예외이며 다른 열의 값은 저장된 그대로다.
   */
  private SlackWorkspace normalize(SlackWorkspace w) {
    if (w.signingSecretEnc() != null) {
      return w;
    }
    return new SlackWorkspace(
        w.id(),
        w.teamId(),
        w.teamName(),
        w.botUserId(),
        w.botTokenEnc(),
        "",
        w.previousSigningSecretEnc(),
        w.previousSigningSecretExpiresAt(),
        w.installedByUserId());
  }
}
