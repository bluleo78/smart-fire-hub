package com.smartfirehub.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.PipelineDeleteImpactResponse.DownstreamChainTrigger;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.dto.TriggerType;
import com.smartfirehub.pipeline.dto.UpdateTriggerRequest;
import com.smartfirehub.pipeline.repository.TriggerRepository.ChainEdgeRow;
import com.smartfirehub.user.repository.AccessFixtures.TriggerStored;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * TriggerRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-708). 감시 목록 정리(removeWatchedDataset·
 * clearPollSnapshot)는 {@link TriggerRepositoryPollSnapshotContractTest} 가 맡고, 여기서는 나머지 공개 메서드 전부를
 * 본다.
 *
 * <p>pipeline_trigger 는 테넌트 정책(RLS) 아래 있어 호출은 늘 테넌트 컨텍스트({@code inA}/{@code inB})로 감싼다. config·
 * trigger_state 는 JSON 열(PG jsonb / Tibero JSON)이고 {@code ->>} 추출은 Tibero 에서 JSON_VALUE, 토큰·웹훅 조회는
 * 유일 함수 색인과 같은 CASE 식이다. 시각 판정은 픽스처로 정해 넣은 과거 시각과 비교해 벽시계에 기대지 않는다.
 */
public abstract class TriggerRepositoryContractTest extends TenantPairContractBase {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 수정 시각 유지·갱신 판정용 과거 기준 시각(초 단위). */
  private static final LocalDateTime PAST = LocalDateTime.of(2020, 1, 2, 3, 4, 5);

  protected abstract TriggerRepository repository();

  private long user;
  private long pipelineA;

  @BeforeEach
  void createPipelineAndUser() {
    user = fx.createUser(prefix + "-u", null);
    pipelineA = fx.createPipeline(tenantA, prefix + "-pa", true);
  }

  private static Map<String, Object> parse(String json) {
    try {
      return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException(json, e);
    }
  }

  private long trigger(long pipeline, String name, String type, String configJson) {
    long id = fx.createTrigger(pipeline, prefix + "-" + name, type, configJson);
    fx.setTriggerUpdatedAt(id, PAST);
    return id;
  }

  private TriggerResponse find(long id) {
    return inA(() -> repository().findById(id)).orElseThrow();
  }

  // ---- 생성·조회 ----

  @Test
  @DisplayName("create — 값이 왕복하고 켜짐·빈 상태로 생기며, 생성·수정 시각은 DB 시계다")
  void create_roundTrips() {
    Map<String, Object> config = Map.of("cron", "0 0 * * *", "nested", Map.of("a", List.of(1, 2)));

    long id =
        inA(
            () ->
                repository()
                    .create(
                        pipelineA,
                        new CreateTriggerRequest(
                            prefix + "-new", TriggerType.SCHEDULE, "설명", config),
                        config,
                        user));

    TriggerResponse t = find(id);
    assertThat(t.id()).isEqualTo(id);
    assertThat(t.pipelineId()).isEqualTo(pipelineA);
    assertThat(t.triggerType()).isEqualTo("SCHEDULE");
    assertThat(t.name()).isEqualTo(prefix + "-new");
    assertThat(t.description()).isEqualTo("설명");
    assertThat(t.isEnabled()).isTrue();
    assertThat(t.config()).isEqualTo(config);
    assertThat(t.triggerState()).isEmpty();
    assertThat(t.nextFireTime()).isNull();
    assertThat(t.createdBy()).isEqualTo(user);
    LocalDateTime now = fx.dbWallClockNow();
    assertThat(Duration.between(t.createdAt(), now).abs()).isLessThan(Duration.ofMinutes(2));
    assertThat(Duration.between(t.updatedAt(), now).abs()).isLessThan(Duration.ofMinutes(2));
    assertThat(fx.triggerStored(id).updatedBy()).isNull();
  }

  @Test
  @DisplayName("create — config 가 null·빈 맵이면 {} 로, 빈 설명은 DB 의 빈 문자열 의미대로 저장한다")
  void create_emptyConfigAndDescription() {
    long nullConfig =
        inA(
            () ->
                repository()
                    .create(
                        pipelineA,
                        new CreateTriggerRequest(prefix + "-a", TriggerType.API, "", null),
                        null,
                        user));
    long emptyConfig =
        inA(
            () ->
                repository()
                    .create(
                        pipelineA,
                        new CreateTriggerRequest(prefix + "-b", TriggerType.API, null, Map.of()),
                        Map.of(),
                        user));

    assertThat(find(nullConfig).config()).isEmpty();
    assertThat(find(emptyConfig).config()).isEmpty();
    assertThat(parse(fx.triggerStored(nullConfig).configJson())).isEmpty();
    assertThat(find(nullConfig).description()).isEqualTo(fx.emptyStringIsNull() ? null : "");
    assertThat(find(emptyConfig).description()).isNull();
  }

  @Test
  @DisplayName("findById — 꺼진 트리거는 nextFireTime 을 필드·상태 원본 모두에서 뺀다. 없는 id·다른 테넌트는 빈 값")
  void findById_masksNextFireTimeWhenDisabled() {
    long on = trigger(pipelineA, "on", "SCHEDULE", "{}");
    long off = trigger(pipelineA, "off", "SCHEDULE", "{}");
    fx.setTriggerState(on, "{\"nextFireTime\":\"2026-01-01T00:00:00Z\",\"k\":1}");
    fx.setTriggerState(off, "{\"nextFireTime\":\"2026-01-01T00:00:00Z\",\"k\":1}");
    fx.setTriggerEnabled(off, false);

    TriggerResponse enabled = find(on);
    assertThat(enabled.nextFireTime()).isEqualTo("2026-01-01T00:00:00Z");
    assertThat(enabled.triggerState())
        .isEqualTo(Map.of("nextFireTime", "2026-01-01T00:00:00Z", "k", 1));
    assertThat(enabled.updatedAt()).isEqualTo(PAST);

    TriggerResponse disabled = find(off);
    assertThat(disabled.isEnabled()).isFalse();
    assertThat(disabled.nextFireTime()).isNull();
    assertThat(disabled.triggerState()).isEqualTo(Map.of("k", 1));

    assertThat(inA(() -> repository().findById(-1L))).isEmpty();
    assertThat(inB(() -> repository().findById(on))).isEmpty();
  }

  @Test
  @DisplayName("findById — 수정 시각이 빈 옛 행은 updatedAt 이 null 이다")
  void findById_nullUpdatedAt() {
    long id = trigger(pipelineA, "old", "API", "{}");
    fx.setTriggerUpdatedAt(id, null);

    assertThat(find(id).updatedAt()).isNull();
  }

  @Test
  @DisplayName("findByPipelineId — 그 파이프라인 트리거만 id 순으로, 다른 테넌트에서는 보이지 않는다")
  void findByPipelineId_orderedAndScoped() {
    long t1 = trigger(pipelineA, "t1", "API", "{}");
    long t2 = trigger(pipelineA, "t2", "SCHEDULE", "{}");
    long otherPipeline = fx.createPipeline(tenantA, prefix + "-px", true);
    trigger(otherPipeline, "t3", "API", "{}");

    assertThat(inA(() -> repository().findByPipelineId(pipelineA)))
        .extracting(TriggerResponse::id)
        .containsExactly(t1, t2);
    assertThat(inB(() -> repository().findByPipelineId(pipelineA))).isEmpty();
  }

  @Test
  @DisplayName("findEnabledByType — 켜진 그 유형만, 다른 테넌트 트리거는 섞이지 않는다")
  void findEnabledByType() {
    long s1 = trigger(pipelineA, "s1", "SCHEDULE", "{}");
    long s2 = trigger(pipelineA, "s2", "SCHEDULE", "{}");
    long off = trigger(pipelineA, "s3", "SCHEDULE", "{}");
    fx.setTriggerEnabled(off, false);
    trigger(pipelineA, "api", "API", "{}");
    long pipelineB = fx.createPipeline(tenantB, prefix + "-pb", true);
    trigger(pipelineB, "sb", "SCHEDULE", "{}");

    assertThat(inA(() -> repository().findEnabledByType("SCHEDULE")))
        .extracting(TriggerResponse::id)
        .containsExactlyInAnyOrder(s1, s2);
  }

  // ---- 연쇄(PIPELINE_CHAIN) ----

  @Test
  @DisplayName("findEnabledChainTriggersByUpstreamId — 숫자·문자열 상류 id 모두 맞히고 꺼진·다른 유형·다른 상류는 뺀다")
  void findEnabledChainTriggersByUpstreamId() {
    long up = fx.createPipeline(tenantA, prefix + "-up", true);
    long numeric =
        trigger(pipelineA, "c1", "PIPELINE_CHAIN", "{\"upstreamPipelineId\":" + up + "}");
    long text =
        trigger(pipelineA, "c2", "PIPELINE_CHAIN", "{\"upstreamPipelineId\":\"" + up + "\"}");
    long off = trigger(pipelineA, "c3", "PIPELINE_CHAIN", "{\"upstreamPipelineId\":" + up + "}");
    fx.setTriggerEnabled(off, false);
    trigger(pipelineA, "c4", "PIPELINE_CHAIN", "{\"upstreamPipelineId\":" + (up + 1000) + "}");
    trigger(pipelineA, "c5", "API", "{\"upstreamPipelineId\":" + up + "}");

    List<TriggerResponse> found = inA(() -> repository().findEnabledChainTriggersByUpstreamId(up));

    assertThat(found).extracting(TriggerResponse::id).containsExactlyInAnyOrder(numeric, text);
    assertThat(inB(() -> repository().findEnabledChainTriggersByUpstreamId(up))).isEmpty();
  }

  @Test
  @DisplayName("findEnabledChainEdges — 켜진 연쇄 간선을 상류 id 원문 그대로(숫자로 못 읽는 값·빠진 값 포함) 읽는다")
  void findEnabledChainEdges() {
    long a = trigger(pipelineA, "e1", "PIPELINE_CHAIN", "{\"upstreamPipelineId\":42}");
    long b = trigger(pipelineA, "e2", "PIPELINE_CHAIN", "{\"upstreamPipelineId\":\"abc\"}");
    long c = trigger(pipelineA, "e3", "PIPELINE_CHAIN", "{}");
    long off = trigger(pipelineA, "e4", "PIPELINE_CHAIN", "{\"upstreamPipelineId\":1}");
    fx.setTriggerEnabled(off, false);
    trigger(pipelineA, "e5", "SCHEDULE", "{\"upstreamPipelineId\":1}");
    long pipelineB = fx.createPipeline(tenantB, prefix + "-pb", true);
    trigger(pipelineB, "eb", "PIPELINE_CHAIN", "{\"upstreamPipelineId\":42}");

    assertThat(inA(() -> repository().findEnabledChainEdges()))
        .containsExactlyInAnyOrder(
            new ChainEdgeRow(a, pipelineA, "42"),
            new ChainEdgeRow(b, pipelineA, "abc"),
            new ChainEdgeRow(c, pipelineA, null));
  }

  @Test
  @DisplayName("lockChainGraph — 호출자 트랜잭션 안에서는 잠그고, 트랜잭션 없이 부르면 MANDATORY 위반이다")
  void lockChainGraph_mandatory() {
    runA(() -> repository().lockChainGraph(tenantA));

    assertThatThrownBy(
            () ->
                fx.noTenant(
                    () -> {
                      repository().lockChainGraph(tenantA);
                      return null;
                    }))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  @DisplayName("findEnabledDownstreamChainTriggers — 하류 파이프라인 이름·트리거 id 순, 자기 파이프라인·꺼진 트리거는 뺀다")
  void findEnabledDownstreamChainTriggers() {
    long up = fx.createPipeline(tenantA, prefix + "-up", true);
    long pz = fx.createPipeline(tenantA, prefix + "-zz", true);
    long pb = fx.createPipeline(tenantA, prefix + "-bb", true);
    String cfg = "{\"upstreamPipelineId\":" + up + "}";
    long z1 = trigger(pz, "z1", "PIPELINE_CHAIN", cfg);
    long b1 = trigger(pb, "b1", "PIPELINE_CHAIN", cfg);
    long b2 = trigger(pb, "b2", "PIPELINE_CHAIN", cfg);
    trigger(up, "self", "PIPELINE_CHAIN", cfg);
    long off = trigger(pb, "b3", "PIPELINE_CHAIN", cfg);
    fx.setTriggerEnabled(off, false);

    List<DownstreamChainTrigger> rows =
        inA(() -> repository().findEnabledDownstreamChainTriggers(up));

    assertThat(rows)
        .containsExactly(
            new DownstreamChainTrigger(b1, prefix + "-b1", pb, prefix + "-bb"),
            new DownstreamChainTrigger(b2, prefix + "-b2", pb, prefix + "-bb"),
            new DownstreamChainTrigger(z1, prefix + "-z1", pz, prefix + "-zz"));
    assertThat(inB(() -> repository().findEnabledDownstreamChainTriggers(up))).isEmpty();
  }

  @Test
  @DisplayName("disableByUpstreamPipelineId — 그 상류의 연쇄 트리거를 모두 끄고(이미 꺼진 것도 센다) 수정 시각을 새로 쓴다")
  void disableByUpstreamPipelineId() {
    long up = fx.createPipeline(tenantA, prefix + "-up", true);
    String cfg = "{\"upstreamPipelineId\":" + up + "}";
    long c1 = trigger(pipelineA, "c1", "PIPELINE_CHAIN", cfg);
    long c2 = trigger(pipelineA, "c2", "PIPELINE_CHAIN", "{\"upstreamPipelineId\":\"" + up + "\"}");
    long off = trigger(pipelineA, "c3", "PIPELINE_CHAIN", cfg);
    fx.setTriggerEnabled(off, false);
    long other =
        trigger(pipelineA, "c4", "PIPELINE_CHAIN", "{\"upstreamPipelineId\":" + (up + 1000) + "}");
    long api = trigger(pipelineA, "api", "API", cfg);
    long pipelineB = fx.createPipeline(tenantB, prefix + "-pb", true);
    long inB = trigger(pipelineB, "cb", "PIPELINE_CHAIN", cfg);

    int count = inA(() -> repository().disableByUpstreamPipelineId(up));

    assertThat(count).isEqualTo(3);
    assertThat(fx.triggerStored(c1).enabled()).isFalse();
    assertThat(fx.triggerStored(c2).enabled()).isFalse();
    assertThat(fx.triggerStored(c1).updatedAt()).isAfter(PAST);
    assertThat(fx.triggerStored(off).updatedAt()).isAfter(PAST);
    assertThat(fx.triggerStored(other).enabled()).isTrue();
    assertThat(fx.triggerStored(api).enabled()).isTrue();
    assertThat(fx.triggerStored(inB).enabled()).isTrue();
    assertThat(fx.triggerStored(inB).updatedAt()).isEqualTo(PAST);
  }

  // ---- 수정·삭제 ----

  @Test
  @DisplayName("lockUpdatedAtById — 있으면 수정 시각, 빈 수정 시각은 null 버전, 없는 id·다른 테넌트는 빈 값")
  void lockUpdatedAtById() {
    long id = trigger(pipelineA, "l1", "API", "{}");
    long blank = trigger(pipelineA, "l2", "API", "{}");
    fx.setTriggerUpdatedAt(blank, null);

    assertThat(inA(() -> repository().lockUpdatedAtById(id))).contains(new LockedRowVersion(PAST));
    assertThat(inA(() -> repository().lockUpdatedAtById(blank)))
        .contains(new LockedRowVersion(null));
    assertThat(inA(() -> repository().lockUpdatedAtById(-1L))).isEmpty();
    assertThat(inB(() -> repository().lockUpdatedAtById(id))).isEmpty();
  }

  @Test
  @DisplayName("update — 준 필드만 바꾸고 빈 설명은 지우며, 수정자·수정 시각을 새로 쓴다")
  void update_partialFields() {
    long id = trigger(pipelineA, "u1", "SCHEDULE", "{\"cron\":\"a\"}");
    runA(
        () ->
            repository()
                .update(
                    id, new UpdateTriggerRequest(prefix + "-renamed", false, "새 설명", null), user));

    TriggerStored stored = fx.triggerStored(id);
    TriggerResponse t = find(id);
    assertThat(t.name()).isEqualTo(prefix + "-renamed");
    assertThat(t.description()).isEqualTo("새 설명");
    assertThat(t.isEnabled()).isFalse();
    assertThat(t.config()).isEqualTo(Map.of("cron", "a"));
    assertThat(stored.updatedBy()).isEqualTo(user);
    assertThat(stored.updatedAt()).isAfter(PAST);

    // 이름·설명·켜짐이 null 이면 그대로, config 만 바꾼다. 공백 설명은 "지움"이다.
    runA(
        () ->
            repository()
                .update(id, new UpdateTriggerRequest(null, null, null, Map.of("cron", "b")), user));
    t = find(id);
    assertThat(t.name()).isEqualTo(prefix + "-renamed");
    assertThat(t.description()).isEqualTo("새 설명");
    assertThat(t.isEnabled()).isFalse();
    assertThat(t.config()).isEqualTo(Map.of("cron", "b"));

    runA(() -> repository().update(id, new UpdateTriggerRequest(null, true, "  ", null), user));
    t = find(id);
    assertThat(t.description()).isNull();
    assertThat(t.isEnabled()).isTrue();
  }

  @Test
  @DisplayName("update — 다른 테넌트 트리거는 바뀌지 않는다")
  void update_otherTenantUntouched() {
    long id = trigger(pipelineA, "u2", "API", "{}");

    fx.inTenant(
        tenantB,
        () -> {
          repository().update(id, new UpdateTriggerRequest("hacked", false, null, null), user);
          return null;
        });

    assertThat(find(id).name()).isEqualTo(prefix + "-u2");
    assertThat(fx.triggerStored(id).updatedAt()).isEqualTo(PAST);
  }

  @Test
  @DisplayName("delete — 지운 행 수를 돌려주고 두 번째·다른 테넌트는 0 이다")
  void delete() {
    long id = trigger(pipelineA, "d1", "API", "{}");

    assertThat(inB(() -> repository().delete(id))).isZero();
    assertThat(inA(() -> repository().delete(id))).isEqualTo(1);
    assertThat(inA(() -> repository().delete(id))).isZero();
    assertThat(fx.triggerStored(id)).isNull();
  }

  @Test
  @DisplayName("updateEnabled — 켜짐을 바꾸고 수정 시각을 새로 쓴다")
  void updateEnabled() {
    long id = trigger(pipelineA, "e1", "API", "{}");

    runA(() -> repository().updateEnabled(id, false));
    assertThat(fx.triggerStored(id).enabled()).isFalse();
    assertThat(fx.triggerStored(id).updatedAt()).isAfter(PAST);

    runA(() -> repository().updateEnabled(id, true));
    assertThat(fx.triggerStored(id).enabled()).isTrue();
  }

  // ---- 실행 상태 ----

  @Test
  @DisplayName("updateTriggerState — 상태를 통째로 바꾸고 수정 시각은 건드리지 않는다. null·빈 맵은 {}")
  void updateTriggerState_keepsUpdatedAt() {
    long id = trigger(pipelineA, "s1", "SCHEDULE", "{}");
    fx.setTriggerState(id, "{\"old\":1}");

    runA(
        () ->
            repository().updateTriggerState(id, Map.of("lastSnapshot", Map.of("1", 2), "x", "y")));
    assertThat(parse(fx.triggerStored(id).stateJson()))
        .isEqualTo(Map.of("lastSnapshot", Map.of("1", 2), "x", "y"));
    assertThat(fx.triggerStored(id).updatedAt()).isEqualTo(PAST);

    runA(() -> repository().updateTriggerState(id, null));
    assertThat(parse(fx.triggerStored(id).stateJson())).isEmpty();
    runA(() -> repository().updateTriggerState(id, Map.of()));
    assertThat(parse(fx.triggerStored(id).stateJson())).isEmpty();
    assertThat(fx.triggerStored(id).updatedAt()).isEqualTo(PAST);
  }

  @Test
  @DisplayName(
      "mergeNextFireTime·clearNextFireTime — 다른 상태 키를 남긴 채 nextFireTime 만 넣고 지운다(수정 시각 불변)")
  void mergeAndClearNextFireTime() {
    long id = trigger(pipelineA, "n1", "SCHEDULE", "{}");
    fx.setTriggerState(id, "{\"k\":1}");

    runA(() -> repository().mergeNextFireTime(id, "2026-05-01T00:00:00Z"));
    assertThat(parse(fx.triggerStored(id).stateJson()))
        .isEqualTo(Map.of("k", 1, "nextFireTime", "2026-05-01T00:00:00Z"));

    runA(() -> repository().clearNextFireTime(id));
    assertThat(parse(fx.triggerStored(id).stateJson())).isEqualTo(Map.of("k", 1));
    assertThat(fx.triggerStored(id).updatedAt()).isEqualTo(PAST);

    // 없는 id 는 아무 일도 없다.
    runA(() -> repository().mergeNextFireTime(-1L, "x"));
    runA(() -> repository().clearNextFireTime(-1L));
  }

  @Test
  @DisplayName("…InTransaction — 호출자 트랜잭션 안에서 넣고 지우며, 트랜잭션 없이 부르면 MANDATORY 위반이다")
  void inTransactionVariants() {
    long id = trigger(pipelineA, "n2", "SCHEDULE", "{}");

    runA(() -> repository().mergeNextFireTimeInTransaction(id, "2026-06-01T00:00:00Z"));
    assertThat(parse(fx.triggerStored(id).stateJson()))
        .isEqualTo(Map.of("nextFireTime", "2026-06-01T00:00:00Z"));
    runA(() -> repository().clearNextFireTimeInTransaction(id));
    assertThat(parse(fx.triggerStored(id).stateJson())).isEmpty();

    assertThatThrownBy(
            () ->
                fx.noTenant(
                    () -> {
                      repository().mergeNextFireTimeInTransaction(id, "x");
                      return null;
                    }))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(
            () ->
                fx.noTenant(
                    () -> {
                      repository().clearNextFireTimeInTransaction(id);
                      return null;
                    }))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  @DisplayName("mergeNextFireTime — 꺼진 트리거는 읽을 때 가린 상태에 새 값을 넣는다(jOOQ 와 같은 읽기-수정-쓰기)")
  void mergeNextFireTime_onDisabledTrigger() {
    long id = trigger(pipelineA, "n3", "SCHEDULE", "{}");
    fx.setTriggerState(id, "{\"nextFireTime\":\"old\",\"k\":2}");
    fx.setTriggerEnabled(id, false);

    runA(() -> repository().mergeNextFireTime(id, "new"));

    Map<String, Object> expected = new HashMap<>(Map.of("k", 2, "nextFireTime", "new"));
    assertThat(parse(fx.triggerStored(id).stateJson())).isEqualTo(expected);
  }

  // ---- 외부 식별자 ----

  @Test
  @DisplayName("findByTokenHash·findByWebhookId — 유형이 맞는 트리거만, 꺼진 것도 찾고 다른 테넌트·없는 값은 빈 값")
  void findByExternalIdentifier() {
    long api = trigger(pipelineA, "api", "API", "{\"tokenHash\":\"" + prefix + "-h\"}");
    long hook = trigger(pipelineA, "hook", "WEBHOOK", "{\"webhookId\":\"" + prefix + "-w\"}");
    fx.setTriggerEnabled(hook, false);
    // 다른 유형의 같은 값은 맞히지 않는다(색인의 CASE 식이 유형을 거른다).
    trigger(
        pipelineA,
        "s",
        "SCHEDULE",
        "{\"tokenHash\":\"" + prefix + "-w\",\"webhookId\":\"" + prefix + "-h\"}");

    assertThat(inA(() -> repository().findByTokenHash(prefix + "-h")).map(TriggerResponse::id))
        .contains(api);
    Optional<TriggerResponse> found = inA(() -> repository().findByWebhookId(prefix + "-w"));
    assertThat(found.map(TriggerResponse::id)).contains(hook);
    assertThat(found.orElseThrow().isEnabled()).isFalse();

    assertThat(inA(() -> repository().findByTokenHash(prefix + "-w"))).isEmpty();
    assertThat(inA(() -> repository().findByWebhookId(prefix + "-h"))).isEmpty();
    assertThat(inA(() -> repository().findByTokenHash(prefix + "-none"))).isEmpty();
    assertThat(inB(() -> repository().findByTokenHash(prefix + "-h"))).isEmpty();
    assertThat(inB(() -> repository().findByWebhookId(prefix + "-w"))).isEmpty();
  }

  // ---- 파이프라인 보조 조회 ----

  @Test
  @DisplayName("findOutputDatasetIds — 스텝 출력 데이터셋 id 를 중복·null 없이 돌려준다")
  void findOutputDatasetIds() {
    long ds1 = fx.createDataset(tenantA, prefix + "-d1");
    long ds2 = fx.createDataset(tenantA, prefix + "-d2");
    fx.createStep(pipelineA, "s1", "SQL", null, ds1, "REPLACE");
    fx.createStep(pipelineA, "s2", "SQL", null, ds1, "APPEND");
    fx.createStep(pipelineA, "s3", "SQL", null, ds2, "REPLACE");
    fx.createStep(pipelineA, "s4", "SQL", null, null, "REPLACE");

    assertThat(inA(() -> repository().findOutputDatasetIds(pipelineA)))
        .containsExactlyInAnyOrder(ds1, ds2);
    assertThat(inB(() -> repository().findOutputDatasetIds(pipelineA))).isEmpty();
  }

  @Test
  @DisplayName("hasRunningExecution — PENDING·RUNNING 실행이 있을 때만 true")
  void hasRunningExecution() {
    fx.createExecution(pipelineA, "COMPLETED");
    assertThat(inA(() -> repository().hasRunningExecution(pipelineA))).isFalse();

    fx.createExecution(pipelineA, "PENDING");
    assertThat(inA(() -> repository().hasRunningExecution(pipelineA))).isTrue();
    assertThat(inB(() -> repository().hasRunningExecution(pipelineA))).isFalse();

    long p2 = fx.createPipeline(tenantA, prefix + "-p2", true);
    fx.createExecution(p2, "RUNNING");
    assertThat(inA(() -> repository().hasRunningExecution(p2))).isTrue();
  }

  @Test
  @DisplayName("isPipelineActive — 활성 플래그 그대로, 없는 파이프라인·다른 테넌트는 false")
  void isPipelineActive() {
    long inactive = fx.createPipeline(tenantA, prefix + "-off", false);

    assertThat(inA(() -> repository().isPipelineActive(pipelineA))).isTrue();
    assertThat(inA(() -> repository().isPipelineActive(inactive))).isFalse();
    assertThat(inA(() -> repository().isPipelineActive(-1L))).isFalse();
    assertThat(inB(() -> repository().isPipelineActive(pipelineA))).isFalse();
  }
}
