package com.smartfirehub.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.pipeline.repository.AiInferenceCacheRepository.Entry;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AiInferenceCacheRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-708).
 *
 * <p>ai_inference_cache 는 테넌트 정책(RLS) 아래 있고 같은 (행 해시, 프롬프트 버전) 이 테넌트마다 따로 저장된다(R2). 구현은 클래스 단위
 * 트랜잭션이 없어 호출은 늘 테넌트 컨텍스트({@code inA}/{@code inB})로 감싼다. JSON 은 DB 마다 공백·키 순서 표기가 달라 문자열이 아니라 트리로
 * 비교한다.
 */
public abstract class AiInferenceCacheRepositoryContractTest extends TenantPairContractBase {

  private static final ObjectMapper JSON = new ObjectMapper();

  protected abstract AiInferenceCacheRepository repository();

  private static JsonNode tree(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception e) {
      throw new AssertionError("JSON 이 아니다: " + json, e);
    }
  }

  private String hash(String s) {
    return prefix + "-" + s;
  }

  @Test
  @DisplayName("넣은 결과 JSON 을 같은 키로 조회하면 JSON 이 그대로 왕복한다")
  void insertThenFind_roundTripsJson() {
    String json = "{\"label\":\"긴급\",\"score\":0.5,\"tags\":[\"a\",\"b\"],\"nested\":{\"k\":1}}";
    runA(() -> repository().insertIgnoringConflicts("v1", List.of(new Entry(hash("h1"), json))));

    var found = inA(() -> repository().findResultJson(hash("h1"), "v1", tenantA));

    assertThat(found).isPresent();
    assertThat(tree(found.get())).isEqualTo(tree(json));
  }

  @Test
  @DisplayName("없는 키·다른 프롬프트 버전은 캐시 미스(빈 값)다")
  void find_missAndOtherPromptVersion() {
    runA(
        () ->
            repository()
                .insertIgnoringConflicts("v1", List.of(new Entry(hash("h1"), "{\"a\":1}"))));

    assertThat(inA(() -> repository().findResultJson(hash("none"), "v1", tenantA))).isEmpty();
    assertThat(inA(() -> repository().findResultJson(hash("h1"), "v2", tenantA))).isEmpty();
  }

  @Test
  @DisplayName("같은 (행 해시, 버전) 을 두 번 넣어도 한 행이고 먼저 들어간 값이 남는다")
  void insertTwice_keepsFirstAndOneRow() {
    runA(
        () ->
            repository()
                .insertIgnoringConflicts("v1", List.of(new Entry(hash("h1"), "{\"n\":1}"))));
    runA(
        () ->
            repository()
                .insertIgnoringConflicts("v1", List.of(new Entry(hash("h1"), "{\"n\":2}"))));

    assertThat(fx.countAiInferenceCache(tenantA, hash("h1"), "v1")).isEqualTo(1);
    var found = inA(() -> repository().findResultJson(hash("h1"), "v1", tenantA));
    assertThat(tree(found.orElseThrow())).isEqualTo(tree("{\"n\":1}"));
  }

  @Test
  @DisplayName("한 호출 안에 같은 키가 둘이어도 한 행만 남고, 새 키·기존 키가 섞여도 새 키만 더해진다")
  void insertMany_duplicatesInsideOneCallAndMixedWithExisting() {
    runA(
        () ->
            repository()
                .insertIgnoringConflicts("v1", List.of(new Entry(hash("old"), "{\"n\":0}"))));

    runA(
        () ->
            repository()
                .insertIgnoringConflicts(
                    "v1",
                    List.of(
                        new Entry(hash("a"), "{\"n\":1}"),
                        new Entry(hash("a"), "{\"n\":99}"),
                        new Entry(hash("old"), "{\"n\":77}"),
                        new Entry(hash("b"), "{\"n\":2}"))));

    assertThat(fx.countAiInferenceCache(tenantA, hash("a"), "v1")).isEqualTo(1);
    assertThat(fx.countAiInferenceCache(tenantA, hash("b"), "v1")).isEqualTo(1);
    assertThat(fx.countAiInferenceCache(tenantA, hash("old"), "v1")).isEqualTo(1);
    assertThat(tree(inA(() -> repository().findResultJson(hash("a"), "v1", tenantA)).orElseThrow()))
        .isEqualTo(tree("{\"n\":1}"));
    assertThat(
            tree(inA(() -> repository().findResultJson(hash("old"), "v1", tenantA)).orElseThrow()))
        .isEqualTo(tree("{\"n\":0}"));
  }

  @Test
  @DisplayName("같은 버전이 다르면 같은 해시도 별개 행이다")
  void sameHashDifferentVersion_areSeparateRows() {
    runA(
        () ->
            repository().insertIgnoringConflicts("v1", List.of(new Entry(hash("h"), "{\"v\":1}"))));
    runA(
        () ->
            repository().insertIgnoringConflicts("v2", List.of(new Entry(hash("h"), "{\"v\":2}"))));

    assertThat(tree(inA(() -> repository().findResultJson(hash("h"), "v1", tenantA)).orElseThrow()))
        .isEqualTo(tree("{\"v\":1}"));
    assertThat(tree(inA(() -> repository().findResultJson(hash("h"), "v2", tenantA)).orElseThrow()))
        .isEqualTo(tree("{\"v\":2}"));
  }

  @Test
  @DisplayName("테넌트 격리 — 같은 키가 테넌트마다 따로 저장되고, 다른 테넌트 값은 히트로 보이지 않는다")
  void tenantIsolation() {
    runA(
        () ->
            repository()
                .insertIgnoringConflicts("v1", List.of(new Entry(hash("h"), "{\"who\":\"A\"}"))));

    // B 컨텍스트에서 B 로 조회하면 A 의 행은 미스다.
    assertThat(inB(() -> repository().findResultJson(hash("h"), "v1", tenantB))).isEmpty();
    // A 컨텍스트에서도 다른 테넌트 id 를 명시하면 미스다(명시 술어).
    assertThat(inA(() -> repository().findResultJson(hash("h"), "v1", tenantB))).isEmpty();

    // B 가 같은 키를 넣으면 B 몫의 별도 행이 생기고 A 의 값은 그대로다.
    inB(
        () -> {
          repository()
              .insertIgnoringConflicts("v1", List.of(new Entry(hash("h"), "{\"who\":\"B\"}")));
          return null;
        });
    assertThat(fx.countAiInferenceCache(tenantA, hash("h"), "v1")).isEqualTo(1);
    assertThat(fx.countAiInferenceCache(tenantB, hash("h"), "v1")).isEqualTo(1);
    assertThat(tree(inA(() -> repository().findResultJson(hash("h"), "v1", tenantA)).orElseThrow()))
        .isEqualTo(tree("{\"who\":\"A\"}"));
    assertThat(tree(inB(() -> repository().findResultJson(hash("h"), "v1", tenantB)).orElseThrow()))
        .isEqualTo(tree("{\"who\":\"B\"}"));
  }

  @Test
  @DisplayName("tenant_id 는 컨텍스트 DEFAULT 가 채우고, created_at 은 DB 시계로 기록된다")
  void insert_fillsTenantAndDbClock() {
    runA(
        () ->
            repository().insertIgnoringConflicts("v1", List.of(new Entry(hash("h"), "{\"a\":1}"))));

    assertThat(fx.countAiInferenceCache(tenantA, hash("h"), "v1")).isEqualTo(1);
    LocalDateTime createdAt = fx.aiInferenceCacheCreatedAt(tenantA, hash("h"), "v1");
    assertThat(createdAt).isNotNull();
    assertThat(Duration.between(createdAt, fx.dbWallClockNow()).abs())
        .isLessThan(Duration.ofMinutes(2));
  }

  @Test
  @DisplayName("500 건이 넘는 한 호출(중복·기존 키·큰 JSON 포함)도 모두 들어가고 중복은 앞의 값, 기존 키는 옛 값이 남는다")
  void insertMany_overChunkSizeWithDuplicates() {
    runA(
        () ->
            repository()
                .insertIgnoringConflicts("v1", List.of(new Entry(hash("k700"), "{\"old\":true}"))));
    // 조각 경계(500)를 넘기는 1001 개 + 첫 키 중복(뒤 값은 버려진다) + 이미 있는 키 — 구현은 조각마다 다건 문장 하나로 넣는다.
    // k600 은 VARCHAR2 한도(65532 바이트)를 넘는 큰 JSON — Tibero 다건 MERGE 소스에 실으면 형 불일치(-11022)라 한 건 경로를 탄다.
    String big = "{\"text\":\"" + "가".repeat(30_000) + "\"}";
    List<Entry> entries = new java.util.ArrayList<>();
    for (int i = 0; i < 1001; i++) {
      entries.add(new Entry(hash("k" + i), i == 600 ? big : "{\"i\":" + i + "}"));
    }
    entries.add(new Entry(hash("k0"), "{\"dup\":true}"));
    runA(() -> repository().insertIgnoringConflicts("v1", entries));

    for (int i : new int[] {0, 499, 500, 600, 700, 999, 1000}) {
      assertThat(fx.countAiInferenceCache(tenantA, hash("k" + i), "v1")).as("k" + i).isEqualTo(1);
    }
    assertThat(
            tree(inA(() -> repository().findResultJson(hash("k0"), "v1", tenantA)).orElseThrow()))
        .isEqualTo(tree("{\"i\":0}"));
    assertThat(
            tree(inA(() -> repository().findResultJson(hash("k700"), "v1", tenantA)).orElseThrow()))
        .isEqualTo(tree("{\"old\":true}"));
    assertThat(
            tree(
                inA(() -> repository().findResultJson(hash("k1000"), "v1", tenantA)).orElseThrow()))
        .isEqualTo(tree("{\"i\":1000}"));
    assertThat(
            tree(inA(() -> repository().findResultJson(hash("k600"), "v1", tenantA)).orElseThrow()))
        .isEqualTo(tree(big));
  }

  @Test
  @DisplayName("빈 목록을 넘기면 아무것도 하지 않는다")
  void insert_emptyListIsNoop() {
    runA(() -> repository().insertIgnoringConflicts("v1", List.of()));

    assertThat(fx.countAiInferenceCache(tenantA, hash("h"), "v1")).isZero();
  }
}
