package com.smartfirehub.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UserChannelBindingRepository 계약 테스트 — 공개 메서드 5 개 전부(WD-709). jOOQ·MyBatis 는 PG, TiberoMyBatis 는
 * Tibero 실서버(두 계정 RLS)다.
 *
 * <p>핵심 계약: workspace_id 가 있으면 (user, channel, workspace) 가 같은 upsert 는 한 행을 갱신하고,
 * NULL(EMAIL·KAKAO)이면 유일 제약이 NULL 을 서로 다른 값으로 보므로 두 번 upsert 하면 두 행이다(PG NULLS DISTINCT 와 같은 결과).
 * 토큰은 더미 암호문이다.
 */
abstract class UserChannelBindingRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract UserChannelBindingRepository repository();

  private long user;
  private long wsA1;
  private long wsA2;
  private String team1;
  private String team2;

  @BeforeEach
  void setUpUserAndWorkspaces() {
    user = fx.createUser(prefix + "-u", null);
    String uniq = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    team1 = "T1" + uniq;
    team2 = "T2" + uniq;
    wsA1 = fx.createSlackWorkspace(tenantA, team1, false);
    wsA2 = fx.createSlackWorkspace(tenantA, team2, false);
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MILLIS);
  }

  private UserChannelBinding binding(
      ChannelType type, Long ws, String ext, String access, String status, Instant created) {
    Instant t = now();
    return new UserChannelBinding(
        null,
        user,
        type,
        ws,
        ext,
        "addr-" + ext,
        access,
        "refresh-" + access,
        t.plus(Duration.ofHours(1)),
        status,
        t,
        created,
        created);
  }

  private void upsertInA(UserChannelBinding b) {
    runA(() -> repository().upsert(b));
  }

  private static void assertClose(Instant actual, Instant expected) {
    assertThat(Duration.between(actual, expected).abs()).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  @DisplayName(
      "workspace 가 있는 upsert 는 한 행을 갱신하고 external_user_id·display_address·created_at 은 보존한다")
  void upsert_withWorkspace_updatesSameRow() {
    Instant created = now().minus(Duration.ofDays(1));
    upsertInA(binding(ChannelType.SLACK, wsA1, "U1", "acc-1", "ACTIVE", created));
    UserChannelBinding first =
        inA(() -> repository().findActive(user, ChannelType.SLACK)).orElseThrow();

    Instant expires = now().plus(Duration.ofHours(5));
    UserChannelBinding second =
        new UserChannelBinding(
            null,
            user,
            ChannelType.SLACK,
            wsA1,
            "U-OTHER",
            "addr-other",
            "acc-2",
            null,
            expires,
            "TOKEN_EXPIRED",
            null,
            now(),
            now());
    upsertInA(second);

    List<UserChannelBinding> all = inA(() -> repository().findByUser(user));
    assertThat(all).hasSize(1);
    UserChannelBinding b = all.get(0);
    assertThat(b.id()).isEqualTo(first.id());
    assertThat(b.accessTokenEnc()).isEqualTo("acc-2");
    assertThat(b.refreshTokenEnc()).isNull();
    assertClose(b.tokenExpiresAt(), expires);
    assertThat(b.status()).isEqualTo("TOKEN_EXPIRED");
    assertThat(b.lastVerifiedAt()).isNull();
    assertThat(b.externalUserId()).isEqualTo("U1");
    assertThat(b.displayAddress()).isEqualTo("addr-U1");
    assertClose(b.createdAt(), created);
    assertThat(b.updatedAt()).isAfter(first.updatedAt().minusSeconds(1));
    assertThat(b.workspaceId()).isEqualTo(wsA1);
  }

  @Test
  @DisplayName("다른 workspace 는 다른 행이다")
  void upsert_differentWorkspace_isNewRow() {
    upsertInA(binding(ChannelType.SLACK, wsA1, "U1", "acc-1", "ACTIVE", now()));
    upsertInA(binding(ChannelType.SLACK, wsA2, "U1", "acc-2", "ACTIVE", now()));

    assertThat(inA(() -> repository().findByUser(user))).hasSize(2);
  }

  @Test
  @DisplayName("workspace 가 NULL 인 EMAIL 을 두 번 upsert 하면 두 행이다(NULL 은 서로 다른 값)")
  void upsert_nullWorkspace_insertsEachTime() {
    upsertInA(binding(ChannelType.EMAIL, null, "e1", "acc-1", "ACTIVE", now()));
    upsertInA(binding(ChannelType.EMAIL, null, "e1", "acc-2", "ACTIVE", now()));

    List<UserChannelBinding> all = inA(() -> repository().findByUser(user));
    assertThat(all).hasSize(2);
    assertThat(all).allSatisfy(b -> assertThat(b.workspaceId()).isNull());
  }

  @Test
  @DisplayName("findActive 는 ACTIVE 중 가장 최근 생성분이고 REVOKED·다른 채널은 건너뛴다")
  void findActive_newestActive() {
    Instant base = now().minus(Duration.ofHours(3));
    upsertInA(binding(ChannelType.KAKAO, null, "k-old", "acc-old", "ACTIVE", base));
    upsertInA(binding(ChannelType.KAKAO, null, "k-new", "acc-new", "ACTIVE", base.plusSeconds(60)));
    upsertInA(
        binding(ChannelType.KAKAO, null, "k-rev", "acc-rev", "REVOKED", base.plusSeconds(120)));

    UserChannelBinding b =
        inA(() -> repository().findActive(user, ChannelType.KAKAO)).orElseThrow();
    assertThat(b.externalUserId()).isEqualTo("k-new");
    assertThat(b.channelType()).isEqualTo(ChannelType.KAKAO);
    assertThat(inA(() -> repository().findActive(user, ChannelType.EMAIL))).isEmpty();
    assertThat(inA(() -> repository().findActive(user + 987_654L, ChannelType.KAKAO))).isEmpty();
  }

  @Test
  @DisplayName("findByUser 는 status 무관하게 채널 종류 오름차순, 같은 채널은 최근 생성순이다")
  void findByUser_order() {
    Instant base = now().minus(Duration.ofHours(3));
    upsertInA(binding(ChannelType.SLACK, wsA1, "s", "acc-s", "REVOKED", base));
    upsertInA(binding(ChannelType.KAKAO, null, "k-old", "acc-k1", "ACTIVE", base));
    upsertInA(binding(ChannelType.KAKAO, null, "k-new", "acc-k2", "ACTIVE", base.plusSeconds(60)));
    upsertInA(binding(ChannelType.EMAIL, null, "e", "acc-e", "ACTIVE", base));

    List<UserChannelBinding> all = inA(() -> repository().findByUser(user));

    assertThat(all)
        .extracting(UserChannelBinding::externalUserId)
        .containsExactly("e", "k-new", "k-old", "s");
    UserChannelBinding slack = all.get(3);
    assertThat(slack.status()).isEqualTo("REVOKED");
    assertThat(slack.accessTokenEnc()).isEqualTo("acc-s");
    assertThat(slack.refreshTokenEnc()).isEqualTo("refresh-acc-s");
    assertThat(slack.id()).isNotNull();
    assertThat(slack.userId()).isEqualTo(user);
  }

  @Test
  @DisplayName("revoke(workspace 값)은 그 workspace 의 행만 REVOKED 로 바꾼다")
  void revoke_withWorkspace() {
    upsertInA(binding(ChannelType.SLACK, wsA1, "U1", "acc-1", "ACTIVE", now()));
    upsertInA(binding(ChannelType.SLACK, wsA2, "U1", "acc-2", "ACTIVE", now()));

    runA(() -> repository().revoke(user, ChannelType.SLACK, wsA1));

    List<UserChannelBinding> all = inA(() -> repository().findByUser(user));
    assertThat(all)
        .filteredOn(b -> b.workspaceId() == wsA1)
        .singleElement()
        .extracting(UserChannelBinding::status)
        .isEqualTo("REVOKED");
    assertThat(all)
        .filteredOn(b -> b.workspaceId() == wsA2)
        .singleElement()
        .extracting(UserChannelBinding::status)
        .isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("revoke(null)은 workspace 가 NULL 인 해당 채널 행만 바꾸고 다른 채널은 그대로다")
  void revoke_nullWorkspace() {
    upsertInA(binding(ChannelType.KAKAO, null, "k", "acc-k", "ACTIVE", now()));
    upsertInA(binding(ChannelType.EMAIL, null, "e", "acc-e", "ACTIVE", now()));
    upsertInA(binding(ChannelType.SLACK, wsA1, "s", "acc-s", "ACTIVE", now()));

    runA(() -> repository().revoke(user, ChannelType.KAKAO, null));

    assertThat(inA(() -> repository().findActive(user, ChannelType.KAKAO))).isEmpty();
    assertThat(inA(() -> repository().findActive(user, ChannelType.EMAIL))).isPresent();
    assertThat(inA(() -> repository().findActive(user, ChannelType.SLACK))).isPresent();
  }

  @Test
  @DisplayName("revoke(null)은 workspace 가 있는 같은 채널 행은 건드리지 않는다")
  void revoke_nullDoesNotTouchWorkspaceRows() {
    upsertInA(binding(ChannelType.SLACK, wsA1, "s", "acc-s", "ACTIVE", now()));

    runA(() -> repository().revoke(user, ChannelType.SLACK, null));

    assertThat(inA(() -> repository().findActive(user, ChannelType.SLACK))).isPresent();
  }

  @Test
  @DisplayName("findByExternalId 는 team_id 의 활성 SLACK binding 만 돌려주고 없는 조합·REVOKED 는 empty 이다")
  void findByExternalId() {
    upsertInA(binding(ChannelType.SLACK, wsA1, "UEXT1", "acc-1", "ACTIVE", now()));
    upsertInA(binding(ChannelType.SLACK, wsA2, "UEXT2", "acc-2", "ACTIVE", now()));

    UserChannelBinding b = inA(() -> repository().findByExternalId(team1, "UEXT1")).orElseThrow();
    assertThat(b.workspaceId()).isEqualTo(wsA1);
    assertThat(b.userId()).isEqualTo(user);
    assertThat(b.accessTokenEnc()).isEqualTo("acc-1");

    // 다른 팀의 외부 id 로는 맞지 않는다.
    assertThat(inA(() -> repository().findByExternalId(team1, "UEXT2"))).isEmpty();
    assertThat(inA(() -> repository().findByExternalId(team2 + "x", "UEXT2"))).isEmpty();
    assertThat(inA(() -> repository().findByExternalId(team2, "UEXT2"))).isPresent();

    runA(() -> repository().revoke(user, ChannelType.SLACK, wsA1));
    assertThat(inA(() -> repository().findByExternalId(team1, "UEXT1"))).isEmpty();
  }

  @Test
  @DisplayName("binding 은 테넌트별로 격리된다 — 다른 테넌트에서는 보이지 않는다")
  void bindings_areTenantScoped() {
    upsertInA(binding(ChannelType.KAKAO, null, "k", "acc-k", "ACTIVE", now()));
    upsertInA(binding(ChannelType.SLACK, wsA1, "UEXT1", "acc-1", "ACTIVE", now()));

    assertThat(inB(() -> repository().findByUser(user))).isEmpty();
    assertThat(inB(() -> repository().findActive(user, ChannelType.KAKAO))).isEmpty();
    assertThat(inB(() -> repository().findByExternalId(team1, "UEXT1"))).isEmpty();

    // B 에서의 revoke 는 A 의 행에 영향이 없다.
    inB(
        () -> {
          repository().revoke(user, ChannelType.KAKAO, null);
          return null;
        });
    assertThat(inA(() -> repository().findActive(user, ChannelType.KAKAO))).isPresent();
  }

  /**
   * 유일 키는 테넌트를 포함한다(PG uk_user_channel(tenant_id, …)·Tibero CASE 함수 색인). B 의 upsert 는 RLS 로 A 의 행을
   * 못 보므로 같은 (user, SLACK, workspace) 라도 B 의 새 행이 되고 A 의 행은 그대로여야 한다.
   */
  @Test
  @DisplayName("다른 테넌트가 같은 (사용자, SLACK, workspace) 를 upsert 하면 그 테넌트의 새 행이 되고 원래 행은 그대로다")
  void upsert_sameKeyInOtherTenant_isNewRowThere() {
    upsertInA(binding(ChannelType.SLACK, wsA1, "UA", "acc-a", "ACTIVE", now()));
    inB(
        () -> {
          repository().upsert(binding(ChannelType.SLACK, wsA1, "UB", "acc-b", "ACTIVE", now()));
          return null;
        });

    List<UserChannelBinding> inTenantB = inB(() -> repository().findByUser(user));
    assertThat(inTenantB).hasSize(1);
    assertThat(inTenantB.get(0).accessTokenEnc()).isEqualTo("acc-b");
    assertThat(inTenantB.get(0).workspaceId()).isEqualTo(wsA1);

    List<UserChannelBinding> inTenantA = inA(() -> repository().findByUser(user));
    assertThat(inTenantA).hasSize(1);
    assertThat(inTenantA.get(0).accessTokenEnc()).as("A 의 행은 바뀌지 않는다").isEqualTo("acc-a");
    assertThat(inTenantA.get(0).externalUserId()).isEqualTo("UA");
  }
}
