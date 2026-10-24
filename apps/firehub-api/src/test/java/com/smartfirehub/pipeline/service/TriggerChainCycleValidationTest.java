package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.exception.CyclicTriggerDependencyException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PausedTransactionRace;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파이프라인 연쇄(PIPELINE_CHAIN) 저장 시 순환 검사의 진입점 통합 테스트(WD-106). 그래프 규칙(순환·다이아몬드·길이·기존 순환)은 {@link
 * ChainGraphTest} 가 DB 없이 보고, 여기서는 생성·수정·토글 세 진입점이 검사를 실제로 거치는지와 안내 문구 배선만 본다.
 *
 * <p>결함: 예전 검사는 upstream 의 하류에서 target 을 찾아(=중복 경로 검사) A↔B 순환은 통과시키고 순환이 아닌 다이아몬드는 거부했다. 여기서는 "새 간선
 * upstream → target 을 더하면 target 에서 하류를 따라 upstream 에 닿는가"를 순환으로 보는지, 연쇄가 활성 상태가 되는 모든 경로(생성·수정·
 * 활성화 전환)가 검사를 거치는지 본다.
 *
 * <p>표기: {@code chain(B, A)} 는 "A 가 끝나면 B 를 실행"(B 에 붙은 트리거, 상류 A) = 간선 A → B.
 */
@Transactional
class TriggerChainCycleValidationTest extends IntegrationTestBase {

  @Autowired private TriggerService triggerService;
  @Autowired private PipelineService pipelineService;
  @Autowired private DSLContext dsl;

  private Long userId;
  private String unique;

  @BeforeEach
  void setUp() {
    unique = String.valueOf(System.nanoTime());
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "chain_cycle_" + unique)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Chain Cycle User")
            .set(USER.EMAIL, "chain_cycle_" + unique + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
  }

  // ------------------------------------------------------------------ 순환 판정

  @Test
  void twoCycle_isRejectedWithKoreanPathMessage() {
    Long a = pipeline("A");
    Long b = pipeline("B");
    chain(b, a); // A → B

    // B → A 를 더하면 A → B → A 순환. 예전 검사는 B 의 하류(없음)만 봐서 통과시켰다.
    assertThatThrownBy(() -> chain(a, b))
        .isInstanceOf(CyclicTriggerDependencyException.class)
        .hasMessageContaining("순환")
        .hasMessageContaining(label("B") + " → " + label("A") + " → " + label("B"));
  }

  @Test
  void diamond_isAllowed() {
    // 결함 보고의 재현: 10 → 11 → 2 가 있을 때 10 → 2 추가는 순환이 아니다(예전엔 409).
    Long p10 = pipeline("P10");
    Long p11 = pipeline("P11");
    Long p2 = pipeline("P2");
    chain(p11, p10);
    chain(p2, p11);

    assertThatCode(() -> chain(p2, p10)).doesNotThrowAnyException();
  }

  @Test
  void selfReference_isRejectedInKorean() {
    Long a = pipeline("A");
    assertThatThrownBy(() -> chain(a, a))
        .isInstanceOf(CyclicTriggerDependencyException.class)
        .hasMessageContaining("자기 자신");
  }

  @Test
  void disabledTrigger_isExcludedFromCycleCheck() {
    Long a = pipeline("A");
    Long b = pipeline("B");
    Long ab = chain(b, a); // A → B
    triggerService.toggleTrigger(ab, false);

    // 꺼진 A → B 는 실행 시 발화하지 않으므로 간선이 아니다 — B → A 를 더해도 순환이 아니다(기존 의미 유지).
    assertThatCode(() -> chain(a, b)).doesNotThrowAnyException();
  }

  @Test
  void enablingViaToggle_thatClosesCycle_isRejected() {
    Long a = pipeline("A");
    Long b = pipeline("B");
    Long ab = chain(b, a); // A → B
    triggerService.toggleTrigger(ab, false);
    chain(a, b); // B → A (A → B 가 꺼져 있어 허용)

    // 다시 켜면 A → B → A 순환이 생긴다 — 활성화 전환 경로도 검사해야 한다.
    assertThatThrownBy(() -> triggerService.toggleTrigger(ab, true))
        .isInstanceOf(CyclicTriggerDependencyException.class)
        .hasMessageContaining("순환");
    assertThat(triggerService.getTriggerById(ab).isEnabled()).isFalse();
  }

  @Test
  void enablingViaUpdate_thatClosesCycle_isRejected() {
    Long a = pipeline("A");
    Long b = pipeline("B");
    Long ab = chain(b, a);
    triggerService.toggleTrigger(ab, false);
    chain(a, b);

    // config 없이 isEnabled 만 true 로 보내는 수정도 간선을 되살린다.
    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    ab, new UpdateTriggerRequest("chain", true, null, null), userId))
        .isInstanceOf(CyclicTriggerDependencyException.class);
    assertThat(triggerService.getTriggerById(ab).isEnabled()).isFalse();
  }

  @Test
  void updateUpstream_thatClosesCycle_isRejected() {
    Long a = pipeline("A");
    Long b = pipeline("B");
    Long c = pipeline("C");
    chain(b, a); // A → B
    Long ca = chain(a, c); // C → A

    // C → A 를 B → A 로 바꾸면 A → B → A.
    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    ca, new UpdateTriggerRequest("chain", null, null, chainConfig(b)), userId))
        .isInstanceOf(CyclicTriggerDependencyException.class);
  }

  @Test
  void stringUpstreamId_isCheckedLikeNumber() {
    // 실행 시 조회는 (config->>'upstreamPipelineId')::bigint 라 문자열 "123" 도 살아 있는 간선이다 — 저장 검사도 같게 읽어야 한다.
    Long a = pipeline("A");
    Long b = pipeline("B");
    chain(b, a); // A → B
    Long ca = chain(a, pipeline("C"));

    Map<String, Object> config = new HashMap<>();
    config.put("upstreamPipelineId", String.valueOf(b));
    config.put("condition", "SUCCESS");
    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    ca, new UpdateTriggerRequest("chain", null, null, config), userId))
        .isInstanceOf(CyclicTriggerDependencyException.class);

    // 숫자로 읽을 수 없는 값은 잘못된 요청으로 거부한다(검사를 건너뛴 채 저장되지 않게).
    config.put("upstreamPipelineId", "not-a-number");
    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    ca, new UpdateTriggerRequest("chain", null, null, config), userId))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void updateUpstream_ofDisabledTrigger_isNotCheckedUntilEnabled() {
    Long a = pipeline("A");
    Long b = pipeline("B");
    Long c = pipeline("C");
    chain(b, a); // A → B
    Long ca = chain(a, c);
    triggerService.toggleTrigger(ca, false);

    // 꺼진 트리거는 간선이 아니다 — 상류를 B 로 바꾸는 것만으로는 순환이 없다. 켤 때 거부된다.
    assertThatCode(
            () ->
                triggerService.updateTrigger(
                    ca, new UpdateTriggerRequest("chain", null, null, chainConfig(b)), userId))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> triggerService.toggleTrigger(ca, true))
        .isInstanceOf(CyclicTriggerDependencyException.class);
  }

  @Test
  void renamingTrigger_withoutEdgeChange_isNotRechecked() {
    // 결함 이전에 저장된 순환(직접 삽입)도 이름 변경 등으로 고칠 수 있어야 한다 — 실행은 깊이 방어가 막는다.
    Long a = pipeline("A");
    Long b = pipeline("B");
    insertChainTrigger(b, a);
    Long ba = insertChainTrigger(a, b);

    assertThatCode(
            () ->
                triggerService.updateTrigger(
                    ba, new UpdateTriggerRequest("renamed", null, null, null), userId))
        .doesNotThrowAnyException();
  }

  @Test
  void addingIntoLegacyCycle_isRejectedWithExistingCycleGuidance() {
    // DB 에 이미 A ↔ B 순환이 있으면(결함 이전 저장분) 그 순환으로 이어지는 C → A 는 실행이 끝없이 이어진다 — 순환을 먼저 끊으라고 안내한다.
    Long a = pipeline("A");
    Long b = pipeline("B");
    Long c = pipeline("C");
    insertChainTrigger(b, a);
    insertChainTrigger(a, b);

    assertThatThrownBy(() -> chain(a, c))
        .isInstanceOf(CyclicTriggerDependencyException.class)
        .hasMessageContaining("이미")
        .hasMessageContaining(label("A"))
        .hasMessageContaining(label("B"));
  }

  // ------------------------------------------------------------------ 선행 파이프라인 존재(WD-218)

  @Test
  void create_withMissingUpstream_isRejectedInKorean() {
    // 결함: 없는 파이프라인 id 를 선행으로 생성하면 201·활성이었다 — 영원히 발화하지 않는 트리거가 생겼다.
    Long b = pipeline("B");
    Long gone = pipeline("Gone");
    pipelineService.deletePipeline(gone);

    assertThatThrownBy(() -> chain(b, gone))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(TriggerService.UPSTREAM_MISSING_MESSAGE);
  }

  @Test
  void upstreamOfOtherTenant_isRejectedWithSameMessageAsMissing() {
    // 다른 테넌트의 파이프라인 id 는 "없음"과 같은 문구·유형이어야 한다 — 문구가 다르면 남의 테넌트 파이프라인 존재를 떠보는 오라클이 된다.
    Long b = pipeline("B");
    long otherTenant = TenantRlsTestSupport.createActiveTenant(dsl, "wd218");
    String prev =
        dsl.resultQuery("select coalesce(current_setting('app.tenant_id', true), '')")
            .fetchOneInto(String.class);
    Long foreign;
    dsl.execute("select set_config('app.tenant_id', ?, true)", String.valueOf(otherTenant));
    try {
      foreign =
          dsl.insertInto(table(name("pipeline")))
              .set(field(name("name"), String.class), "wd218-foreign-" + unique)
              .set(field(name("is_active"), Boolean.class), true)
              .set(field(name("created_by"), Long.class), userId)
              .returning(field(name("id"), Long.class))
              .fetchOne()
              .get(field(name("id"), Long.class));
    } finally {
      dsl.execute("select set_config('app.tenant_id', ?, true)", prev);
    }
    assertThat(foreign).as("다른 테넌트 파이프라인이 실제로 만들어져야 단언이 공허하지 않다").isNotNull();

    assertThatThrownBy(() -> chain(b, foreign))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(TriggerService.UPSTREAM_MISSING_MESSAGE);
  }

  @Test
  void deletedUpstream_disablesTrigger_showsReason_andBlocksReEnableAndSave() {
    Long a = pipeline("A");
    Long b = pipeline("B");
    Long ba = chain(b, a); // A → B

    // 선행이 있으면 표시 없음.
    assertThat(triggerService.getTriggerById(ba).upstreamMissing()).isFalse();

    pipelineService.deletePipeline(a);

    // 서버가 자동으로 끈 트리거는 목록·단건 조회에서 이유(선행 없음)를 함께 내려 준다.
    TriggerResponse listed =
        triggerService.getTriggers(b).stream()
            .filter(t -> t.id().equals(ba))
            .findFirst()
            .orElseThrow();
    assertThat(listed.isEnabled()).isFalse();
    assertThat(listed.upstreamMissing()).isTrue();
    assertThat(triggerService.getTriggerById(ba).upstreamMissing()).isTrue();

    // 켜기 거부(결함: 성공 토스트와 함께 「활성」이 됐다).
    assertThatThrownBy(() -> triggerService.toggleTrigger(ba, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(TriggerService.UPSTREAM_MISSING_MESSAGE);
    // config 없이 켜는 수정도 같은 거부.
    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    ba, new UpdateTriggerRequest(null, true, null, null), userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(TriggerService.UPSTREAM_MISSING_MESSAGE);
    // 지워진 id 그대로 저장(웹 편집 창은 늘 config 를 보낸다) 거부(결함: 「수정되었습니다」).
    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    ba, new UpdateTriggerRequest("chain", null, null, chainConfig(a)), userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(TriggerService.UPSTREAM_MISSING_MESSAGE);
    assertThat(triggerService.getTriggerById(ba).isEnabled()).isFalse();

    // 이름만 바꾸거나 끄는 것은 막지 않는다.
    assertThatCode(
            () ->
                triggerService.updateTrigger(
                    ba, new UpdateTriggerRequest("renamed", false, null, null), userId))
        .doesNotThrowAnyException();
    assertThatCode(() -> triggerService.toggleTrigger(ba, false)).doesNotThrowAnyException();

    // 다른(있는) 선행으로 바꾸면 저장되고 표시가 사라진다.
    Long c = pipeline("C");
    triggerService.updateTrigger(
        ba, new UpdateTriggerRequest("chain", null, null, chainConfig(c)), userId);
    assertThat(triggerService.getTriggerById(ba).upstreamMissing()).isFalse();
    assertThatCode(() -> triggerService.toggleTrigger(ba, true)).doesNotThrowAnyException();
  }

  @Test
  void update_toMissingUpstream_isRejected() {
    // 결함: PUT 으로 없는 id(999 등)를 선행으로 바꾸면 204 였다.
    Long a = pipeline("A");
    Long b = pipeline("B");
    Long ba = chain(b, a);
    Long gone = pipeline("Gone");
    pipelineService.deletePipeline(gone);

    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    ba, new UpdateTriggerRequest("chain", null, null, chainConfig(gone)), userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(TriggerService.UPSTREAM_MISSING_MESSAGE);
  }

  @Test
  void legacyEnabledDanglingTrigger_isFlaggedEvenWhileEnabled() {
    // 결함 이전에 없는 선행으로 켜진 채 남은 트리거(운영 잔존분)도 표시로 드러나야 한다 — 켜짐 여부와 무관하게 계산한다.
    Long b = pipeline("B");
    Long gone = pipeline("Gone");
    pipelineService.deletePipeline(gone);
    Long legacy = insertChainTrigger(b, gone);

    TriggerResponse listed =
        triggerService.getTriggers(b).stream()
            .filter(t -> t.id().equals(legacy))
            .findFirst()
            .orElseThrow();
    assertThat(listed.isEnabled()).isTrue();
    assertThat(listed.upstreamMissing()).isTrue();
  }

  // ------------------------------------------------------------------ 동시 저장(WD-239)
  //
  // 결함: F 에 「G 끝나면 실행」, G 에 「F 끝나면 실행」을 동시에 만들면 READ COMMITTED 두 트랜잭션이 서로의 아직 커밋되지 않은 간선을 못 봐 둘 다
  // 저장됐다(순차면 409). 아래 테스트는 실제 커밋이 필요하므로 메서드 단위로 테스트 트랜잭션을 끄고, 만든 파이프라인(트리거는 CASCADE)과 사용자를
  // 직접 지운다.

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentOppositeCreates_secondWaitsAndIsRejected() throws Exception {
    List<Long> created = new ArrayList<>();
    try {
      // 커밋 전에 멈춘 첫 생성 동안 둘째 생성이 대기했는지까지 본다 — 대기하지 않으면 잠금이 경합 구간을 막지 못한 것이다(공허한 통과 방지).
      for (int round = 0; round < 3; round++) {
        Long f = pipeline("PF" + round);
        Long g = pipeline("PG" + round);
        created.add(f);
        created.add(g);

        PausedTransactionRace.Outcome<Long> out =
            PausedTransactionRace.run(
                fixtureTransactionTemplate,
                DEFAULT_TEST_TENANT_ID,
                () -> chain(f, g), // G → F
                () -> chain(g, f)); // F → G — 첫째가 커밋되면 순환

        assertThat(out.secondBlocked()).as("round %d: 둘째 생성이 첫째 커밋까지 대기", round).isTrue();
        assertThat(out.secondError())
            .as("round %d", round)
            .isInstanceOf(CyclicTriggerDependencyException.class)
            .hasMessageContaining("순환");
        assertThat(enabledChainCount(f, g)).as("round %d: 저장된 활성 연쇄", round).isEqualTo(1);
      }
    } finally {
      cleanupCommitted(created);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void freeRunningOppositeCreates_exactlyOneSucceeds() throws Exception {
    List<Long> created = new ArrayList<>();
    try {
      // 멈춤 없이 두 스레드를 동시에 출발시켜 실제 요청 경합에 가깝게 여러 번 반복한다(수정 전에는 둘 다 성공하는 회차가 나왔다).
      for (int round = 0; round < 10; round++) {
        Long f = pipeline("FF" + round);
        Long g = pipeline("FG" + round);
        created.add(f);
        created.add(g);

        List<Throwable> outcomes = raceFreely(() -> chain(f, g), () -> chain(g, f));

        assertThat(outcomes.stream().filter(e -> e == null).count())
            .as("round %d: 성공은 하나", round)
            .isEqualTo(1);
        assertThat(outcomes.stream().filter(e -> e != null).findFirst().orElseThrow())
            .as("round %d: 나머지는 순환 거부", round)
            .isInstanceOf(CyclicTriggerDependencyException.class);
        assertThat(enabledChainCount(f, g)).as("round %d", round).isEqualTo(1);
      }
    } finally {
      cleanupCommitted(created);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentEnableViaToggleAndUpdate_secondWaitsAndIsRejected() throws Exception {
    List<Long> created = new ArrayList<>();
    try {
      // 켜기 토글·수정(isEnabled=true) 두 경로가 서로 엇갈려 켜질 때도 같은 잠금으로 직렬화되는지 — 둘째 자리에 두 경로를 번갈아 둔다.
      for (int round = 0; round < 2; round++) {
        Long f = pipeline("TF" + round);
        Long g = pipeline("TG" + round);
        created.add(f);
        created.add(g);
        Long gf = chain(f, g); // G → F
        triggerService.toggleTrigger(gf, false);
        Long fg = chain(g, f); // F → G (G → F 가 꺼져 있어 허용)
        triggerService.toggleTrigger(fg, false);

        boolean toggleSecond = round == 0;
        Runnable first =
            toggleSecond
                ? () ->
                    triggerService.updateTrigger(
                        gf, new UpdateTriggerRequest("chain", true, null, null), userId)
                : () -> triggerService.toggleTrigger(gf, true);
        PausedTransactionRace.Outcome<Boolean> out =
            PausedTransactionRace.run(
                fixtureTransactionTemplate,
                DEFAULT_TEST_TENANT_ID,
                first,
                () -> {
                  if (toggleSecond) {
                    triggerService.toggleTrigger(fg, true);
                  } else {
                    triggerService.updateTrigger(
                        fg, new UpdateTriggerRequest("chain", true, null, null), userId);
                  }
                  return true;
                });

        String path = toggleSecond ? "켜기 토글" : "수정";
        assertThat(out.secondBlocked()).as("%s: 첫째 커밋까지 대기", path).isTrue();
        assertThat(out.secondError())
            .as(path)
            .isInstanceOf(CyclicTriggerDependencyException.class)
            .hasMessageContaining("순환");
        assertThat(enabledChainCount(f, g)).as(path).isEqualTo(1);
      }
    } finally {
      cleanupCommitted(created);
    }
  }

  /** 두 작업을 같은 순간에 출발시켜 각각의 예외(성공이면 null)를 돌려준다. 각 스레드는 테스트 기본 테넌트 컨텍스트로 프로덕션 트랜잭션을 스스로 연다. */
  private List<Throwable> raceFreely(Runnable a, Runnable b) throws Exception {
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Throwable>> futures = new ArrayList<>();
      for (Runnable work : List.of(a, b)) {
        futures.add(
            pool.submit(
                () -> {
                  TenantContext.set(DEFAULT_TEST_TENANT_ID);
                  try {
                    start.await(10, TimeUnit.SECONDS);
                    work.run();
                    return (Throwable) null;
                  } catch (Throwable e) {
                    return e;
                  } finally {
                    TenantContext.clear();
                  }
                }));
      }
      List<Throwable> outcomes = new ArrayList<>();
      for (Future<Throwable> future : futures) {
        outcomes.add(future.get(30, TimeUnit.SECONDS));
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
    }
  }

  /** 두 파이프라인에 붙은 활성 연쇄 트리거 수(커밋된 값). */
  private int enabledChainCount(Long p1, Long p2) {
    return inTenantFixture(
        () ->
            dsl.fetchCount(
                PIPELINE_TRIGGER,
                PIPELINE_TRIGGER
                    .PIPELINE_ID
                    .in(p1, p2)
                    .and(PIPELINE_TRIGGER.TRIGGER_TYPE.eq("PIPELINE_CHAIN"))
                    .and(PIPELINE_TRIGGER.IS_ENABLED.isTrue())));
  }

  /** 테스트 트랜잭션 없이 커밋된 픽스처를 지운다 — 파이프라인(트리거·이벤트 CASCADE) 다음 사용자 순서여야 FK 로 실패하지 않는다. */
  private void cleanupCommitted(List<Long> pipelineIds) {
    awaitSecurityAudit();
    TenantRlsTestSupport.cleanupAll(
        () -> pipelineIds.forEach(pipelineService::deletePipeline),
        () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID));
  }

  // ------------------------------------------------------------------ 픽스처

  private Long pipeline(String label) {
    return pipelineService
        .createPipeline(new CreatePipelineRequest(pipelineName(label), null, List.of()), userId)
        .id();
  }

  private String pipelineName(String label) {
    return "chain-" + label + "-" + unique;
  }

  /** 안내 문구에 실리는 파이프라인 표기. */
  private String label(String label) {
    return "「" + pipelineName(label) + "」";
  }

  private Map<String, Object> chainConfig(Long upstream) {
    Map<String, Object> config = new HashMap<>();
    config.put("upstreamPipelineId", upstream);
    config.put("condition", "SUCCESS");
    return config;
  }

  /** 서비스 경로로 「upstream 이 끝나면 target 실행」 트리거를 만든다(검사를 거친다). */
  private Long chain(Long target, Long upstream) {
    return triggerService
        .createTrigger(
            target,
            new CreateTriggerRequest(
                "chain", TriggerType.PIPELINE_CHAIN, null, chainConfig(upstream)),
            userId)
        .id();
  }

  /** 검사를 우회해 활성 연쇄 트리거를 직접 넣는다 — 결함 이전에 저장된 순환을 재현하기 위함. */
  private Long insertChainTrigger(Long target, Long upstream) {
    return dsl.insertInto(table(name("pipeline_trigger")))
        .set(field(name("pipeline_id"), Long.class), target)
        .set(field(name("trigger_type"), String.class), "PIPELINE_CHAIN")
        .set(field(name("name"), String.class), "legacy")
        .set(field(name("is_enabled"), Boolean.class), true)
        .set(
            field(name("config"), JSONB.class),
            JSONB.valueOf("{\"upstreamPipelineId\":" + upstream + ",\"condition\":\"SUCCESS\"}"))
        .set(field(name("created_by"), Long.class), userId)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }
}
