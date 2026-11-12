package com.smartfirehub.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.notification.repository.SlackWorkspaceRepository.SlackWorkspace;
import com.smartfirehub.user.repository.AccessFixtures.SlackWorkspaceStored;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SlackWorkspaceRepository 계약 테스트 — 공개 메서드 5 개 전부(WD-709). jOOQ·MyBatis 는 PG, TiberoMyBatis 는
 * Tibero 실서버(두 계정 RLS)다.
 *
 * <p>Review Focus 3: 최초 설치(upsertFromOAuth)가 두 DB 에서 성공하고 signing secret 은 {@code ""} 로 읽히며(Tibero
 * 저장은 NULL), 재설치는 같은 id·revoked_at 복원·signing secret 보존이다. team_id 는 전역 유일이라 시험마다 고유하게 만든다. 암호화 열은
 * 더미 값이다.
 */
abstract class SlackWorkspaceRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract SlackWorkspaceRepository repository();

  private long user;
  private String team;

  @BeforeEach
  void setUpUser() {
    user = fx.createUser(prefix + "-u", null);
    team = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private long installInA(String teamId, String name, String token, Long by) {
    return inA(() -> repository().upsertFromOAuth(teamId, name, "UBOT1", token, by));
  }

  @Test
  @DisplayName("최초 설치는 성공하고 signing secret 은 빈 문자열로 읽힌다")
  void firstInstall_signingSecretReadsEmpty() {
    long id = installInA(team, "Acme", "enc-bot-1", user);

    SlackWorkspace w = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(w.id()).isEqualTo(id);
    assertThat(w.teamId()).isEqualTo(team);
    assertThat(w.teamName()).isEqualTo("Acme");
    assertThat(w.botUserId()).isEqualTo("UBOT1");
    assertThat(w.botTokenEnc()).isEqualTo("enc-bot-1");
    assertThat(w.installedByUserId()).isEqualTo(user);
    assertThat(w.signingSecretEnc()).isEqualTo("");
    assertThat(w.previousSigningSecretEnc()).isNull();
    assertThat(w.previousSigningSecretExpiresAt()).isNull();
    // 저장 모양은 DB 별로 다르다 — Tibero 는 ''=NULL 이라 NULL 로 저장된다(V159.1).
    SlackWorkspaceStored stored = fx.slackWorkspaceStored(id);
    assertThat(stored.signingSecretEnc()).isEqualTo(emptyStringIsNull() ? null : "");
    assertThat(stored.revoked()).isFalse();
  }

  @Test
  @DisplayName("재설치는 같은 id 를 돌려주고 필드를 갱신하며 revoked 를 풀고 signing secret 을 보존한다")
  void reinstall_keepsIdAndSigningSecret() {
    long id = installInA(team, "Acme", "enc-bot-1", user);
    fx.setSlackSigningSecret(id, "enc-sign-1");
    runA(() -> repository().revoke(team));
    assertThat(inA(() -> repository().findByTeamId(team))).isEmpty();
    assertThat(fx.slackWorkspaceStored(id).revoked()).isTrue();

    long other = fx.createUser(prefix + "-u2", null);
    long again = inA(() -> repository().upsertFromOAuth(team, null, "UBOT2", "enc-bot-2", other));

    assertThat(again).isEqualTo(id);
    SlackWorkspace w = inA(() -> repository().findByTeamId(team)).orElseThrow();
    assertThat(w.id()).isEqualTo(id);
    assertThat(w.teamName()).isNull();
    assertThat(w.botUserId()).isEqualTo("UBOT2");
    assertThat(w.botTokenEnc()).isEqualTo("enc-bot-2");
    assertThat(w.installedByUserId()).isEqualTo(other);
    assertThat(w.signingSecretEnc()).isEqualTo("enc-sign-1");
    assertThat(fx.slackWorkspaceStored(id).revoked()).isFalse();
  }

  @Test
  @DisplayName("설치자가 null 이어도 저장된다")
  void install_withoutInstaller() {
    long id = inA(() -> repository().upsertFromOAuth(team, "Acme", "UBOT1", "enc-bot-1", null));

    assertThat(inA(() -> repository().findById(id)).orElseThrow().installedByUserId()).isNull();
  }

  @Test
  @DisplayName("findByTeamId 는 활성만 돌려주고 없는·빈·null team_id 는 empty 이다")
  void findByTeamId_activeOnly() {
    long id = fx.createSlackWorkspace(tenantA, team, false);

    SlackWorkspace w = inA(() -> repository().findByTeamId(team)).orElseThrow();
    assertThat(w.id()).isEqualTo(id);
    assertThat(w.signingSecretEnc()).isEqualTo("enc-sign");
    assertThat(w.botTokenEnc()).isEqualTo("enc-bot");

    assertThat(inA(() -> repository().findByTeamId(team + "-none"))).isEmpty();
    assertThat(inA(() -> repository().findByTeamId(""))).isEmpty();
    assertThat(inA(() -> repository().findByTeamId(null))).isEmpty();

    runA(() -> repository().revoke(team));
    assertThat(inA(() -> repository().findByTeamId(team))).isEmpty();
  }

  @Test
  @DisplayName("폐기된 워크스페이스도 findById 로는 보인다")
  void findById_includesRevoked() {
    long id = fx.createSlackWorkspace(tenantA, team, true);

    assertThat(inA(() -> repository().findById(id))).isPresent();
    assertThat(inA(() -> repository().findByTeamId(team))).isEmpty();
    assertThat(inA(() -> repository().findById(id + 987_654_321L))).isEmpty();
  }

  @Test
  @DisplayName("직전 서명 비밀과 만료 시각이 그대로 읽힌다")
  void previousSigningSecret_roundTrips() {
    long id = fx.createSlackWorkspace(tenantA, team, false);
    Instant expires = Instant.now().plus(Duration.ofHours(1));
    fx.setSlackPreviousSigningSecret(id, "enc-prev", expires);

    SlackWorkspace w = inA(() -> repository().findById(id)).orElseThrow();

    assertThat(w.previousSigningSecretEnc()).isEqualTo("enc-prev");
    assertThat(Duration.between(w.previousSigningSecretExpiresAt(), expires).abs())
        .isLessThan(Duration.ofSeconds(1));
  }

  @Test
  @DisplayName("findFirstActive 는 활성 중 가장 큰 id 이고 폐기분은 건너뛰며 모두 폐기면 empty 이다")
  void findFirstActive_picksNewestActive() {
    assertThat(inA(() -> repository().findFirstActive())).isEmpty();
    long older = fx.createSlackWorkspace(tenantA, team + "a", false);
    long newer = fx.createSlackWorkspace(tenantA, team + "b", false);

    assertThat(inA(() -> repository().findFirstActive()).orElseThrow().id()).isEqualTo(newer);

    runA(() -> repository().revoke(team + "b"));
    assertThat(inA(() -> repository().findFirstActive()).orElseThrow().id()).isEqualTo(older);

    runA(() -> repository().revoke(team + "a"));
    assertThat(inA(() -> repository().findFirstActive())).isEmpty();
  }

  @Test
  @DisplayName("워크스페이스는 테넌트별로 격리되고 다른 테넌트의 revoke 는 영향이 없다")
  void workspaces_areTenantScoped() {
    long a = fx.createSlackWorkspace(tenantA, team + "a", false);
    long b = fx.createSlackWorkspace(tenantB, team + "b", false);

    assertThat(inA(() -> repository().findByTeamId(team + "b"))).isEmpty();
    assertThat(inA(() -> repository().findById(b))).isEmpty();
    assertThat(inB(() -> repository().findById(a))).isEmpty();
    assertThat(inB(() -> repository().findFirstActive()).orElseThrow().id()).isEqualTo(b);

    runA(() -> repository().revoke(team + "b"));
    assertThat(fx.slackWorkspaceStored(b).revoked()).isFalse();
    assertThat(inB(() -> repository().findByTeamId(team + "b"))).isPresent();
  }

  @Test
  @DisplayName("없는 team_id 의 revoke 는 아무 일도 하지 않는다")
  void revoke_unknownIsNoop() {
    long id = fx.createSlackWorkspace(tenantA, team, false);

    runA(() -> repository().revoke(team + "-none"));

    assertThat(fx.slackWorkspaceStored(id).revoked()).isFalse();
  }
}
