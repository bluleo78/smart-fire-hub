package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.NotificationOutboxRepository.NotificationOutboxRow;
import com.smartfirehub.notification.Recipient;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import com.smartfirehub.notification.service.ResolvedRouting;
import com.smartfirehub.notification.service.RoutingResolver;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V107 이 켠 채널 도메인 4테이블({@code slack_workspace}, {@code notification_outbox},
 * {@code user_channel_binding}, {@code user_channel_preference})의 테넌트 격리를 검증한다.
 *
 * <p>왜 양방향인가: "다른 테넌트에서 0행" 단방향 단언은 빈 테이블에서 공허하게 통과한다(P1 에서
 * 실제로 결함을 통과시킨 전례가 있다). 소유 테넌트에서 실제로 보이는 것 + {@code tenant_id} DEFAULT
 * 가 GUC 에서 채워지는 것을 함께 확인해야 의미가 있다({@code assertTwoSidedIsolation} 의 3다리).
 *
 * <p><b>⚠ outbox 는 {@code claimDue}/{@code countPendingByChannel} 로 증명하지 않는다(계획 R20).</b> 그 둘은
 * Task 3 이 <b>명시적 테넌트 술어</b>를 걸어 둔 경로다(정책 이전 구간의 교차테넌트 배달 창을 닫기
 * 위해서였고, 리뷰어가 그 창을 재현했다). 그래서 V107 이 outbox 정책을 통째로 빠뜨려도 두 경로는
 * 여전히 옳은 답을 낸다 — <b>정책 부재를 감지하지 못한다.</b> 이 클래스는 술어 없는 경로로만 증명한다:
 * 원시 {@code select}({@code assertTwoSidedIsolation} 내부), {@code findByCorrelation},
 * {@code findStuckPending}, {@code reclaimZombies}.
 *
 * <p>이 클래스에 클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — 테넌트를 바꿔 가며 여러
 * 트랜잭션을 열어야 하고, 하나의 테스트 트랜잭션에 묶이면 GUC 가 처음 값으로 고정된다. 더 중요하게는
 * 테스트 트랜잭션이 GUC 를 공급해 프로덕션 배선 결함을 영구히 가린다.
 *
 * <p>테스트 커넥션은 비특권 롤 {@code app_tenant}(NOBYPASSRLS, V83)로 접속한다 — 픽스처 생성·정리·
 * 검증 조회에도 정책이 적용되므로 그 셋 모두 테넌트 트랜잭션 안에서 한다.
 *
 * <p>공유 테스트 DB 라 전체 카운트 비교 단언은 쓸 수 없다(다른 세션이 동시에 쓴다). 실행마다 고유한
 * 테넌트 두 개를 만들어 그 범위에서만 단언한다.
 */
class ChannelDomainRlsTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private NotificationOutboxRepository outboxRepo;
  @Autowired private SlackWorkspaceRepository workspaceRepo;
  @Autowired private RoutingResolver routingResolver;

  private long tenantA;
  private long tenantB;
  private Long userA;

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "chanrls-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "chanrls-b");
    // binding/preference 가 user FK 를 요구한다. "user" 는 테넌트 경계 위의 전역 테이블(RLS 없음)이라
    // 컨텍스트 없이 만든다. 두 테넌트가 같은 사용자를 공유해도 유니크가 (tenant_id, ...) 라 무방하다.
    userA = TenantRlsTestSupport.insertUser(dsl, "chanrls");
  }

  @AfterEach
  void tearDown() {
    // RLS 스코프 안에서 지운다 — 밖에서 지우면 0행이 되어 픽스처가 조용히 누적된다.
    deleteOwnRows(tenantA);
    deleteOwnRows(tenantB);
    TenantRlsTestSupport.deleteUser(dsl, userA);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
  }

  private void deleteOwnRows(long tenantId) {
    inTenantFixture(tenantId, () -> TenantRlsTestSupport.deleteChannelCascade(dsl, tenantId));
  }

  // ── 4테이블 양방향 격리 ─────────────────────────────────────────────────

  @Test
  @DisplayName("slack_workspace 는 테넌트 간 양방향으로 격리된다")
  void slackWorkspaceIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        fixtureTransactionTemplate,
        dsl,
        tenantA,
        tenantB,
        "slack_workspace",
        () -> insertWorkspace(newTeamId()));
  }

  @Test
  @DisplayName("notification_outbox 는 테넌트 간 양방향으로 격리된다")
  void outboxIsIsolated() {
    // assertTwoSidedIsolation 의 존재 확인은 원시 fetchCount 라 앱 술어가 없다 → R20 에 저촉되지 않는다.
    TenantRlsTestSupport.assertTwoSidedIsolation(
        fixtureTransactionTemplate,
        dsl,
        tenantA,
        tenantB,
        "notification_outbox",
        () -> insertOutbox(UUID.randomUUID()));
  }

  @Test
  @DisplayName("user_channel_binding 은 테넌트 간 양방향으로 격리된다")
  void bindingIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        fixtureTransactionTemplate,
        dsl,
        tenantA,
        tenantB,
        "user_channel_binding",
        () -> insertBinding("EMAIL"));
  }

  @Test
  @DisplayName("user_channel_preference 는 테넌트 간 양방향으로 격리된다")
  void preferenceIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        fixtureTransactionTemplate,
        dsl,
        tenantA,
        tenantB,
        "user_channel_preference",
        () -> insertPreference("EMAIL"));
  }

  // ── 역방향 단언의 판별력 자기검증 ────────────────────────────────────────

  /**
   * 위 4개 테스트의 "소유 테넌트에서는 보인다" 다리가 <b>실제로 판별력이 있는지</b> 확인한다.
   *
   * <p>정책을 임시로 끄는 방식은 공유 테스트 DB 를 다른 세션과 함께 쓰는 이 프로젝트에서 쓸 수 없다.
   * 대신 <b>스코프를 틀리게 준다</b> — 행은 A 에 만들어 두고 헬퍼에는 소유자가 B 라고 알려 준다.
   * 그러면 "소유 테넌트에서 보인다" 다리가 깨져야 한다. 깨지지 않으면 그 다리는 무엇도 검증하지
   * 않고 있는 것이다.
   *
   * <p>행을 <b>헬퍼 호출 전에</b> 만든다(공급자 람다 안에서 중첩 스코프로 만들지 않는다). GUC 는
   * 트랜잭션-로컬이고 {@code TenantAwareTransactionManager} 가 트랜잭션 <b>시작 시점</b>에만 심으므로,
   * 이미 열린 트랜잭션 안에서 다시 {@code runScoped} 를 걸어도 바깥 테넌트의 GUC 가 그대로 유지된다 —
   * 중첩하면 행이 A 가 아니라 B 에 만들어져 이 자기검증이 거꾸로 공허해진다.
   */
  @Test
  @DisplayName("역방향 단언은 스코프를 틀리게 주면 실패한다 (헬퍼 자기검증)")
  void twoSidedIsolationHelperHasDiscriminatingPower() {
    Long pkInA =
        inTenantFixture(tenantA, () -> insertPreference("SLACK"));

    Throwable thrown =
        catchThrowable(
            () ->
                // 소유자를 B 라고 거짓말한다. 공급자는 이미 만든 A 의 pk 를 그대로 돌려줄 뿐이다.
                TenantRlsTestSupport.assertTwoSidedIsolation(
                    fixtureTransactionTemplate,
                    dsl,
                    tenantB,
                    tenantA,
                    "user_channel_preference",
                    () -> pkInA));

    assertThat(thrown)
        .as("스코프를 틀리게 줬는데도 통과하면 역방향 단언에 판별력이 없다")
        .isInstanceOf(AssertionError.class);
  }

  // ── fail-closed / WITH CHECK ────────────────────────────────────────────

  @Test
  @DisplayName("컨텍스트가 없으면 자기 slack_workspace 도 보이지 않는다 (fail-closed)")
  void workspaceFailsClosedWithoutContext() {
    TenantRlsTestSupport.assertFailsClosedWithoutContext(
        fixtureTransactionTemplate,
        dsl,
        tenantA,
        "slack_workspace",
        () -> insertWorkspace(newTeamId()));
  }

  @Test
  @DisplayName("컨텍스트가 없으면 자기 notification_outbox 도 보이지 않는다 (fail-closed)")
  void outboxFailsClosedWithoutContext() {
    TenantRlsTestSupport.assertFailsClosedWithoutContext(
        fixtureTransactionTemplate,
        dsl,
        tenantA,
        "notification_outbox",
        () -> insertOutbox(UUID.randomUUID()));
  }

  @Test
  @DisplayName("컨텍스트가 없으면 자기 user_channel_binding 도 보이지 않는다 (fail-closed)")
  void bindingFailsClosedWithoutContext() {
    TenantRlsTestSupport.assertFailsClosedWithoutContext(
        fixtureTransactionTemplate,
        dsl,
        tenantA,
        "user_channel_binding",
        () -> insertBinding("KAKAO"));
  }

  @Test
  @DisplayName("컨텍스트가 없으면 자기 user_channel_preference 도 보이지 않는다 (fail-closed)")
  void preferenceFailsClosedWithoutContext() {
    TenantRlsTestSupport.assertFailsClosedWithoutContext(
        fixtureTransactionTemplate,
        dsl,
        tenantA,
        "user_channel_preference",
        () -> insertPreference("KAKAO"));
  }

  @Test
  @DisplayName("다른 테넌트 id 로 slack_workspace 를 심으려 하면 WITH CHECK 가 거부한다")
  void crossTenantWorkspaceInsertIsRejected() {
    TenantRlsTestSupport.assertCrossTenantInsertRejected(
        fixtureTransactionTemplate,
        tenantA,
        "slack_workspace",
        () ->
            dsl.execute(
                "insert into slack_workspace"
                    + " (team_id, bot_user_id, bot_token_enc, signing_secret_enc, tenant_id)"
                    + " values (?, 'B-X', 'enc', '', ?)",
                newTeamId(),
                tenantB));
  }

  @Test
  @DisplayName("다른 테넌트 id 로 notification_outbox 를 심으려 하면 WITH CHECK 가 거부한다")
  void crossTenantOutboxInsertIsRejected() {
    TenantRlsTestSupport.assertCrossTenantInsertRejected(
        fixtureTransactionTemplate,
        tenantA,
        "notification_outbox",
        () -> {
          UUID corr = UUID.randomUUID();
          dsl.execute(
              "insert into notification_outbox"
                  + " (idempotency_key, correlation_id, event_type, channel_type, tenant_id)"
                  + " values (?, ?, 'TEST_XT', 'CHAT', ?)",
              "xt-" + corr,
              corr,
              tenantB);
        });
  }

  @Test
  @DisplayName("다른 테넌트 id 로 user_channel_binding 을 심으려 하면 WITH CHECK 가 거부한다")
  void crossTenantBindingInsertIsRejected() {
    TenantRlsTestSupport.assertCrossTenantInsertRejected(
        fixtureTransactionTemplate,
        tenantA,
        "user_channel_binding",
        () ->
            dsl.execute(
                "insert into user_channel_binding (user_id, channel_type, tenant_id)"
                    + " values (?, 'EMAIL', ?)",
                userA,
                tenantB));
  }

  @Test
  @DisplayName("다른 테넌트 id 로 user_channel_preference 를 심으려 하면 WITH CHECK 가 거부한다")
  void crossTenantPreferenceInsertIsRejected() {
    TenantRlsTestSupport.assertCrossTenantInsertRejected(
        fixtureTransactionTemplate,
        tenantA,
        "user_channel_preference",
        () ->
            dsl.execute(
                "insert into user_channel_preference (user_id, channel_type, tenant_id)"
                    + " values (?, 'EMAIL', ?)",
                userA,
                tenantB));
  }

  // ── UPDATE / DELETE 가 남의 행에 닿지 않는다 ─────────────────────────────

  /**
   * USING 은 SELECT 뿐 아니라 UPDATE/DELETE 의 <b>대상 선정</b>에도 적용된다. 읽기만 검증하면 남의
   * 행을 지우거나 뒤집는 경로가 열려 있어도 초록이 된다 — 4테이블 모두 확인한다.
   */
  @Test
  @DisplayName("남의 행은 id 를 알아도 UPDATE·DELETE 가 0행이다 (4테이블)")
  void crossTenantUpdateAndDeleteAffectNoRows() {
    long wsId = inTenantFixture(tenantA, () -> insertWorkspace(newTeamId()));
    long obId = inTenantFixture(tenantA, () -> insertOutbox(UUID.randomUUID()));
    long bdId = inTenantFixture(tenantA, () -> insertBinding("EMAIL"));
    long prId = inTenantFixture(tenantA, () -> insertPreference("EMAIL"));

    inTenantFixture(tenantB,
        () -> {
          assertThat(dsl.execute("update slack_workspace set team_name = 'hijack' where id = ?", wsId))
              .as("slack_workspace: 남의 행 UPDATE 는 0행이어야 한다")
              .isZero();
          assertThat(dsl.execute("update notification_outbox set status = 'SENT' where id = ?", obId))
              .as("notification_outbox: 남의 행 UPDATE 는 0행이어야 한다")
              .isZero();
          assertThat(dsl.execute("update user_channel_binding set status = 'REVOKED' where id = ?", bdId))
              .as("user_channel_binding: 남의 행 UPDATE 는 0행이어야 한다")
              .isZero();
          assertThat(dsl.execute("update user_channel_preference set enabled = false where id = ?", prId))
              .as("user_channel_preference: 남의 행 UPDATE 는 0행이어야 한다")
              .isZero();

          assertThat(dsl.execute("delete from slack_workspace where id = ?", wsId)).isZero();
          assertThat(dsl.execute("delete from notification_outbox where id = ?", obId)).isZero();
          assertThat(dsl.execute("delete from user_channel_binding where id = ?", bdId)).isZero();
          assertThat(dsl.execute("delete from user_channel_preference where id = ?", prId)).isZero();
        });

    // 역방향 — 소유 테넌트에서는 같은 UPDATE 가 실제로 1행에 닿는다. 없으면 "전부 0행" 인 고장을 못 잡는다.
    inTenantFixture(tenantA,
        () ->
            assertThat(dsl.execute("update slack_workspace set team_name = 'own' where id = ?", wsId))
                .as("소유 테넌트에서는 같은 UPDATE 가 1행에 닿아야 한다")
                .isOne());
  }

  // ── outbox: 앱 술어 없는 리포지토리 경로로만 증명 (R20) ──────────────────

  @Test
  @DisplayName("findByCorrelation 은 남의 correlation 을 0행으로 돌려준다 (술어 없는 경로)")
  void findByCorrelationIsScopedByPolicy() {
    UUID corr = UUID.randomUUID();
    inTenantFixture(tenantA, () -> insertOutbox(corr));

    assertThat(TenantContext.runScopedGet(tenantB, () -> outboxRepo.findByCorrelation(corr)))
        .as("B 가 A 의 correlation 으로 조회하면 0행이어야 한다")
        .isEmpty();
    assertThat(TenantContext.runScopedGet(tenantA, () -> outboxRepo.findByCorrelation(corr)))
        .as("소유 테넌트에서는 보여야 한다 (역방향)")
        .hasSize(1);
  }

  /**
   * {@code findStuckPending} 은 관리자 적체 조회다. <b>이 단언은 이 밴드가 부수적으로 닫는 실제
   * 취약점의 닫힘 증명이다</b> — 정책 이전에는 테넌트 A 의 ADMIN 이 B 의 outbox 행(수신자 id,
   * correlation, payload)을 그대로 봤다.
   */
  @Test
  @DisplayName("findStuckPending 은 남의 적체를 보여주지 않는다 (관리자 교차테넌트 노출 차단)")
  void findStuckPendingIsScopedByPolicy() {
    UUID corr = UUID.randomUUID();
    inTenantFixture(tenantA, () -> insertOutbox(corr));
    // created_at 은 now() 이므로 "미래" 기준으로 조회해야 적체로 잡힌다.
    Instant future = Instant.now().plusSeconds(60);

    assertThat(TenantContext.runScopedGet(tenantB, () -> outboxRepo.findStuckPending(future)))
        .as("B 의 ADMIN 이 A 의 적체 행을 보면 교차테넌트 노출이다")
        .noneMatch(r -> corr.equals(r.correlationId()));
    assertThat(TenantContext.runScopedGet(tenantA, () -> outboxRepo.findStuckPending(future)))
        .as("소유 테넌트의 ADMIN 에게는 보여야 한다 (역방향)")
        .anyMatch(r -> corr.equals(r.correlationId()));
  }

  /**
   * {@code reclaimZombies} 는 술어 없는 전역 UPDATE 다 — <b>UPDATE 정책 프로브로 유일하게 유효한
   * 리포지토리 경로</b>이며, 동시에 {@code OutboxSweeper} 의 테넌트 순회가 판별력을 갖게 된 근거다.
   */
  @Test
  @DisplayName("reclaimZombies 는 남의 좀비를 회수하지 않는다 (UPDATE 정책 프로브)")
  void reclaimZombiesIsScopedByPolicy() {
    UUID corr = UUID.randomUUID();
    inTenantFixture(tenantA, () -> insertOutbox(corr));
    inTenantFixture(tenantA, () -> TenantRlsTestSupport.makeOutboxRowDue(dsl, corr));
    TenantContext.runScoped(tenantA, () -> outboxRepo.claimDue(10_000, "i", tenantA));

    Instant cutoff = Instant.now().plusSeconds(60); // 모든 SENDING 행이 좀비로 보이는 cutoff

    assertThat(TenantContext.runScopedGet(tenantB, () -> outboxRepo.reclaimZombies(cutoff)))
        .as("B 의 스위프가 A 의 좀비를 회수하면 UPDATE 격리가 깨진 것이다")
        .isZero();
    assertThat(
            TenantContext.runScopedGet(
                tenantA, () -> outboxRepo.findByCorrelation(corr).get(0).status()))
        .as("A 의 행은 여전히 SENDING 이어야 한다")
        .isEqualTo("SENDING");

    assertThat(TenantContext.runScopedGet(tenantA, () -> outboxRepo.reclaimZombies(cutoff)))
        .as("소유 테넌트의 스위프는 실제로 회수해야 한다 (역방향)")
        .isEqualTo(1);
  }

  // ── RoutingResolver — 정책 이후 "조용한 열화" 가 실제로 일어나지 않는지 ──────

  /**
   * 조사가 "이 밴드에서 가장 발견하기 어려운 회귀" 로 지목한 경로다.
   *
   * <p><b>무엇이 위험한가.</b> {@code preferenceRepo.isEnabled} 는 행이 없으면 기본 {@code true},
   * {@code bindingRepo.findActive} 는 행이 없으면 0행이다. 정책이 켜진 뒤 컨텍스트가 없으면 두 조회가
   * 모두 "행 없음" 을 보고 <b>바인딩이 있는 채널까지 전부 {@code BINDING_MISSING} 으로 스킵되어
   * CHAT 폴백만 남는다</b> — 예외도 로그도 없이 알림 채널이 통째로 사라진다.
   *
   * <p><b>왜 통합 테스트가 필요한가.</b> {@code RoutingResolverTest} 는 리포지토리를 전부 mock 하므로
   * 정책과 무관하게 통과한다. 실제 행 + 실제 정책으로 한 번은 확인해야 이 회귀가 잡힌다. Task 3 이
   * "enqueue 경로에 컨텍스트가 흐른다" 로 무변경 판정했고 리뷰어가 독립 확인했지만, 그 판정은
   * 호출 그래프 추론이었다 — 여기서 실물로 고정한다.
   */
  @Test
  @DisplayName("정책 이후에도 컨텍스트가 있으면 바인딩된 채널이 그대로 해석된다 (조용한 CHAT 열화 없음)")
  void routingResolvesBoundChannelUnderTenantContext() {
    inTenantFixture(tenantA, () -> insertBinding("SLACK"));

    ResolvedRouting resolved =
        TenantContext.runScopedGet(
            tenantA,
            () ->
                routingResolver.resolve(
                    new Recipient(userA, null, EnumSet.of(ChannelType.SLACK))));

    assertThat(resolved.resolvedChannels())
        .as("바인딩이 있는데 CHAT 으로 폴백되면 정책 이후의 조용한 열화다")
        .containsExactly(ChannelType.SLACK);

    // 대조군 — 같은 사용자라도 바인딩이 없는 테넌트 B 에서는 CHAT 폴백이다. 위 단언이 "무엇을 넣어도
    // SLACK 이 나온다" 는 공허한 통과가 아님을 보인다. 동시에 격리가 라우팅까지 미치는 증거다.
    ResolvedRouting inB =
        TenantContext.runScopedGet(
            tenantB,
            () ->
                routingResolver.resolve(
                    new Recipient(userA, null, EnumSet.of(ChannelType.SLACK))));

    assertThat(inB.resolvedChannels())
        .as("B 에는 바인딩이 없으므로 CHAT 폴백이어야 한다 (A 의 바인딩이 새면 안 된다)")
        .containsExactly(ChannelType.CHAT);
  }

  // ── Slack 팀 재설치 경합 (Task 5 이 넘긴 항목) ───────────────────────────

  /**
   * 같은 Slack 팀을 다른 테넌트가 재설치하면 <b>{@code 42501}</b> 로 fail-closed 된다.
   *
   * <p><b>왜 {@code 23505}(유니크 위반)가 아닌가.</b> {@code team_id} 는 R1 에 따라 전역 유니크로
   * 남아 있고, {@code ON CONFLICT} 의 충돌 판정은 <b>유니크 인덱스</b>가 한다 — 인덱스는 RLS 와
   * 무관하게 모든 행을 보므로 INSERT 경로로 가지 않는다. DO UPDATE 경로로 가서 대상 행의 정책 USING
   * 검사가 실패하고 {@code new row violates row-level security policy (USING expression)} 가 난다.
   * {@code 23505} 를 기대하고 예외 핸들러를 달면 그 핸들러는 영원히 발화하지 않는다.
   *
   * <p><b>왜 이게 옳은 동작인가.</b> 쓰기가 일어나지 않고, A 의 기존 행은 손대지 않으며, 워크스페이스가
   * 잘못된 테넌트에 붙지도 않는다. 사용자향 오류 메시지 개선은 <b>별건 백로그</b>다 — "이미 다른
   * 조직에 설치된 워크스페이스입니다" 류의 4xx 는 친절해 보이지만 <b>다른 테넌트의 설치 사실을
   * 누출</b>한다(V95:12 와 같은 종류의 판단).
   */
  @Test
  @DisplayName("다른 테넌트가 같은 Slack 팀을 재설치하면 42501 로 fail-closed 된다")
  void slackReinstallByAnotherTenantFailsClosedWith42501() {
    String teamId = newTeamId();
    long installedId =
        TenantContext.runScopedGet(
            tenantA, () -> workspaceRepo.upsertFromOAuth(teamId, "A 조직", "B-A", "enc-a", userA));

    Throwable thrown =
        catchThrowable(
            () ->
                TenantContext.runScoped(
                    tenantB,
                    () -> workspaceRepo.upsertFromOAuth(teamId, "B 조직", "B-B", "enc-b", userA)));

    SQLException sqlEx = TenantRlsTestSupport.findSqlException(thrown);
    assertThat((Object) sqlEx).as("SQLException 이 감싸져 있어야 SQLSTATE 를 단언할 수 있다").isNotNull();
    assertThat(sqlEx.getSQLState())
        .as("정책 USING 위반(42501)이어야 한다 — 23505 를 기대하면 핸들러가 영원히 발화하지 않는다")
        .isEqualTo("42501");

    // fail-closed 의 나머지 절반: A 의 기존 행이 손상되지 않았다.
    inTenantFixture(tenantA,
        () ->
            assertThat(
                    dsl.fetchValue(
                        "select team_name from slack_workspace where id = ?", installedId))
                .as("실패한 재설치가 A 의 행을 건드리면 안 된다")
                .isEqualTo("A 조직"));
  }

  /**
   * 위 케이스의 <b>해지 후</b> 변형 — A 가 해지한 뒤 B 가 같은 팀을 설치해도 결과는 같아야 한다.
   *
   * <p><b>왜 따로 두는가.</b> {@code revoke} 는 행을 지우지 않고 {@code revoked_at} 만 채운다. 그래서
   * A 의 관점에서는 "설치가 없는" 상태인데 {@code team_id} 전역 유니크(R1)는 그대로 살아 있어,
   * B 의 {@code ON CONFLICT} 는 여전히 <b>보이지 않는 행</b>을 만난다. 위 케이스가 42501 을 고정했지만
   * 그것은 활성 행일 때의 이야기라, 해지 경로에서 다른 SQLSTATE 가 나거나 {@code doUpdate} 가 0행이
   * 되어 {@code .returning(...).fetchOne()} 이 null → {@code :146} NPE 가 되는 분기가 있는지는
   * 덮이지 않았다. 이 테스트는 <b>실제로 무엇이 일어나는지를 고정</b>한다.
   *
   * <p>사용자향 오류 매핑은 여기서도 하지 않는다 — 구체적 4xx 는 다른 테넌트의 설치 사실을 오라클로
   * 누출한다(위 케이스와 같은 판단, 별건 백로그).
   */
  @Test
  @DisplayName("A 가 해지한 Slack 팀을 B 가 설치해도 42501 로 fail-closed 된다 (NPE 아님)")
  void slackReinstallAfterRevokeByAnotherTenantFailsClosed() {
    String teamId = newTeamId();
    long installedId =
        TenantContext.runScopedGet(
            tenantA, () -> workspaceRepo.upsertFromOAuth(teamId, "A 조직", "B-A", "enc-a", userA));
    TenantContext.runScoped(tenantA, () -> workspaceRepo.revoke(teamId));

    Throwable thrown =
        catchThrowable(
            () ->
                TenantContext.runScoped(
                    tenantB,
                    () -> workspaceRepo.upsertFromOAuth(teamId, "B 조직", "B-B", "enc-b", userA)));

    // NPE 가 아니라 SQLSTATE 있는 DB 오류여야 한다 — NPE 면 원인 추적이 불가능하고 재시도 정책도 없다.
    assertThat(thrown).as("해지 후 재설치도 예외로 끝나야 한다").isNotNull();
    assertThat(thrown)
        .as("fetchOne() null 로 인한 NPE 분기가 있으면 안 된다")
        .isNotInstanceOf(NullPointerException.class);
    SQLException sqlEx = TenantRlsTestSupport.findSqlException(thrown);
    assertThat((Object) sqlEx).as("SQLException 이 감싸져 있어야 SQLSTATE 를 단언할 수 있다").isNotNull();
    assertThat(sqlEx.getSQLState())
        .as("해지 상태여도 행은 A 의 것이므로 정책 USING 위반(42501)이 그대로 나야 한다")
        .isEqualTo("42501");

    // A 의 행은 해지 상태 그대로 남는다 — B 의 실패한 설치가 revoked_at 을 되돌리면 안 된다.
    inTenantFixture(tenantA,
        () -> {
          assertThat(
                  dsl.fetchValue("select team_name from slack_workspace where id = ?", installedId))
              .as("실패한 재설치가 A 의 행을 건드리면 안 된다")
              .isEqualTo("A 조직");
          assertThat(
                  dsl.fetchValue(
                      "select revoked_at from slack_workspace where id = ?", installedId))
              .as("B 의 upsert 가 A 의 해지를 취소(revoked_at=NULL)하면 안 된다")
              .isNotNull();
        });
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────────
  //
  // 전부 bare 삽입이다 — 호출자가 이미 소유 테넌트 트랜잭션을 열어 둔 안에서 실행된다.
  // tenant_id 는 어디에서도 명시하지 않는다 — V106 이 심은 컬럼 DEFAULT 가 GUC 에서 채우는 것을
  // 함께 검증하기 위함이다.

  private static String newTeamId() {
    return "T" + TenantRlsTestSupport.nextTenantId();
  }

  private Long insertWorkspace(String teamId) {
    return (Long)
        dsl.fetchValue(
            "insert into slack_workspace (team_id, bot_user_id, bot_token_enc, signing_secret_enc)"
                + " values (?, 'B-TEST', 'enc', '') returning id",
            teamId);
  }

  private Long insertOutbox(UUID corr) {
    NotificationOutboxRow row =
        new NotificationOutboxRow(
            null,
            "chanrls-" + corr,
            corr,
            "TEST_CHANNEL_RLS",
            null,
            ChannelType.CHAT,
            null,
            null,
            null,
            null,
            "{\"t\":\"x\"}",
            "STANDARD",
            "PENDING",
            0,
            Instant.now());
    outboxRepo.insertIfAbsent(row);
    return (Long) dsl.fetchValue("select id from notification_outbox where correlation_id = ?", corr);
  }

  private Long insertBinding(String channelType) {
    return (Long)
        dsl.fetchValue(
            "insert into user_channel_binding (user_id, channel_type) values (?, ?) returning id",
            userA,
            channelType);
  }

  private Long insertPreference(String channelType) {
    return (Long)
        dsl.fetchValue(
            "insert into user_channel_preference (user_id, channel_type, enabled)"
                + " values (?, ?, true) returning id",
            userA,
            channelType);
  }
}
