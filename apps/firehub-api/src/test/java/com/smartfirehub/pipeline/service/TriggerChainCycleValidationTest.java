package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.exception.CyclicTriggerDependencyException;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
