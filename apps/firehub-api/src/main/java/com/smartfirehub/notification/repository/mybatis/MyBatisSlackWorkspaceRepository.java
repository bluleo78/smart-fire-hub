package com.smartfirehub.notification.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformRetries;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
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

  private final SlackWorkspaceMapper mapper;

  public MyBatisSlackWorkspaceRepository(SlackWorkspaceMapper mapper) {
    this.mapper = mapper;
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
   * <p>다른 테넌트가 이미 설치한 team: team_id 는 테넌트를 가로질러 유일한데, 다른 테넌트에 설치된 행은 RLS 로 보이지 않는다. 그래서 MERGE/ON
   * CONFLICT 가 그 행을 「없음」으로 보고 삽입하려다 막힌다 — Tibero 는 재시도 뒤에도 {@code DuplicateKeyException} 을, PG 는
   * RLS 위반 오류를 낸다. 동작은 jOOQ 와 같게 두며, 호출자는 두 예외 모두 설치 실패로 다룬다(한 가지 오류로 맞추는 일은 서비스 쪽 후속 과제).
   */
  @Override
  public long upsertFromOAuth(
      String teamId,
      String teamName,
      String botUserId,
      String botTokenEnc,
      Long installedByUserId) {
    PlatformRetries.runOnceOnDuplicateKey(
        () -> mapper.upsertFromOAuth(teamId, teamName, botUserId, botTokenEnc, installedByUserId));
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
