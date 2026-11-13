package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository.SourceTextRow;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.LocalDateTime;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DatasetEmbeddingRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-707). dataset_embedding 은 테넌트 정책 아래 있어
 * 호출은 {@code fx.inTenant} 로 감싼다.
 *
 * <p>벡터 테이블 메서드(스펙 D1 꺼진 경로)는 {@link #supportsVectorSearch()} 로 가른다: 참(jOOQ/PG)이면 리포지토리 자신의 메서드만으로
 * 벡터를 쓰고 읽어 의미를 확인하고, 거짓(MyBatis — PG 프로필·Tibero 모두)이면 {@code UnsupportedOperationException} 과 기능
 * 이름을 단언한다. 벡터 저장소의 깊은 동작(차원 이동·HNSW 계획)은 {@code DatasetEmbeddingVectorStoreTest} 가 고정한다.
 */
public abstract class DatasetEmbeddingRepositoryContractTest extends TenantPairContractBase {

  protected abstract DatasetEmbeddingRepository repository();

  /** 벡터 테이블 메서드를 구현하는가(jOOQ 참, MyBatis 거짓 — 스펙 D1). */
  protected boolean supportsVectorSearch() {
    return true;
  }

  private static final EmbeddingAllowance EXTERNAL =
      new EmbeddingAllowance(ProviderHosting.EXTERNAL);
  private static final EmbeddingAllowance SELF_HOSTED =
      new EmbeddingAllowance(ProviderHosting.SELF_HOSTED);
  private static final EmbeddingSpace SPACE =
      new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");

  /** 꺼진 경로 단언 — 예외 종류와 기능 이름. */
  private void assertVectorOff(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("VECTOR_SEARCH");
  }

  private static float[] vec() {
    float[] v = new float[1024];
    v[0] = 1f;
    return v;
  }

  // ---------- 살아 있는 경로 ----------

  @Test
  @DisplayName("upsertSourceText 는 새 행을 만들고(테넌트는 컨텍스트) 두 번째 호출은 같은 행의 본문과 갱신 시각을 바꾼다")
  void upsertSourceText_insertThenUpdate() {
    long ds = fx.createDataset(tenantA, prefix + " ds");

    runA(() -> repository().upsertSourceText(ds, "첫 본문 First"));
    assertThat(fx.embeddingSourceText(ds)).isEqualTo("첫 본문 First");
    assertThat(fx.embeddingTenantId(ds)).isEqualTo(tenantA);

    fx.setEmbeddingUpdatedAt(ds, LocalDateTime.of(2000, 1, 1, 0, 0));
    runA(() -> repository().upsertSourceText(ds, "두 번째 본문 Second"));
    assertThat(fx.embeddingSourceText(ds)).isEqualTo("두 번째 본문 Second");
    assertThat(fx.embeddingUpdatedAt(ds).getYear()).isGreaterThan(2000);
    assertThat(inA(() -> repository().countAll(EXTERNAL))).isEqualTo(1L);
  }

  @Test
  @DisplayName("upsertSourceText 는 4000바이트를 훨씬 넘는 한글 본문도 그대로 저장한다")
  void upsertSourceText_longKoreanText() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    // 한글 3바이트 × 6000 + ASCII 6000 ≈ 24KB — Tibero VARCHAR2(65532) 바이트 한도 안, SQL 식 4000바이트 한도는 넘는다.
    String text = "화재통계".repeat(1500) + "abcdef".repeat(1000);

    runA(() -> repository().upsertSourceText(ds, "짧은 본문"));
    runA(() -> repository().upsertSourceText(ds, text));

    assertThat(fx.embeddingSourceText(ds)).isEqualTo(text);
  }

  @Test
  @DisplayName("빈 본문 — PG 는 빈 문자열로 저장하고, Tibero 는 NULL 이 되어 NOT NULL 위반으로 거절된다")
  void upsertSourceText_emptyText() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    if (emptyStringIsNull()) {
      assertThatThrownBy(() -> runA(() -> repository().upsertSourceText(ds, "")))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
      assertThat(fx.embeddingSourceText(ds)).isNull();
    } else {
      runA(() -> repository().upsertSourceText(ds, ""));
      assertThat(fx.embeddingSourceText(ds)).isEmpty();
    }
  }

  @Test
  @DisplayName("delete 는 카탈로그 행을 지우고, 없는 id 는 예외 없이 넘어간다")
  void delete_removesRow_missingIsNoop() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long keep = fx.createDataset(tenantA, prefix + " keep");
    runA(() -> repository().upsertSourceText(ds, "지울 본문"));
    runA(() -> repository().upsertSourceText(keep, "남길 본문"));

    runA(() -> repository().delete(ds));
    runA(() -> repository().delete(ds));
    runA(() -> repository().delete(-1L));

    assertThat(fx.embeddingSourceText(ds)).isNull();
    assertThat(fx.embeddingSourceText(keep)).isEqualTo("남길 본문");
  }

  @Test
  @DisplayName("다른 테넌트 차단 — B 컨텍스트의 countAll 은 A 행을 세지 않고, B 의 delete·upsert 는 A 행을 건드리지 않는다")
  void otherTenant_isIsolated() {
    long dsA = fx.createDataset(tenantA, prefix + " a");
    long dsB = fx.createDataset(tenantB, prefix + " b");
    runA(() -> repository().upsertSourceText(dsA, "A 본문"));
    fx.inTenant(
        tenantB,
        () -> {
          repository().upsertSourceText(dsB, "B 본문");
          return null;
        });

    assertThat(inA(() -> repository().countAll(EXTERNAL))).isEqualTo(1L);
    assertThat(fx.inTenant(tenantB, () -> repository().countAll(EXTERNAL))).isEqualTo(1L);

    fx.inTenant(
        tenantB,
        () -> {
          repository().delete(dsA);
          return null;
        });
    assertThat(fx.embeddingSourceText(dsA)).isEqualTo("A 본문");
    assertThat(fx.embeddingSourceText(dsB)).isEqualTo("B 본문");
  }

  @Test
  @DisplayName(
      "countAll 은 등급 ai_policy 와 호스팅으로 모집단을 거른다(ALL 은 둘 다, SELF_HOSTED_ONLY 는 자체 호스팅만, DENY 는 없음)")
  void countAll_aiPolicyMatrix() {
    long all = fx.createSecurityLevel(tenantA, 6, prefix + "-all");
    long selfOnly = fx.createSecurityLevel(tenantA, 7, prefix + "-self");
    long deny = fx.createSecurityLevel(tenantA, 8, prefix + "-deny");
    fx.setSecurityLevelAiPolicy(all, "ALL");
    fx.setSecurityLevelAiPolicy(selfOnly, "SELF_HOSTED_ONLY");
    fx.setSecurityLevelAiPolicy(deny, "DENY");
    long dsAll = fx.createDataset(tenantA, prefix + " all");
    long dsSelf = fx.createDataset(tenantA, prefix + " self");
    long dsDeny = fx.createDataset(tenantA, prefix + " deny");
    long dsNoRow = fx.createDataset(tenantA, prefix + " norow");
    fx.setDatasetField(dsAll, "security_level_id", all);
    fx.setDatasetField(dsSelf, "security_level_id", selfOnly);
    fx.setDatasetField(dsDeny, "security_level_id", deny);
    fx.setDatasetField(dsNoRow, "security_level_id", all);
    runA(() -> repository().upsertSourceText(dsAll, "all"));
    runA(() -> repository().upsertSourceText(dsSelf, "self"));
    runA(() -> repository().upsertSourceText(dsDeny, "deny"));

    // 카탈로그 행이 없는 데이터셋(dsNoRow)은 세지 않는다.
    assertThat(inA(() -> repository().countAll(EXTERNAL))).isEqualTo(1L);
    assertThat(inA(() -> repository().countAll(SELF_HOSTED))).isEqualTo(2L);
  }

  // ---------- 벡터 테이블 메서드(스펙 D1) ----------

  @Test
  @DisplayName("벡터 쓰기·세기·지우기 — 꺼진 구현은 쓰기·지우기만 예외(VECTOR_SEARCH), 판정은 표시 열 SQL")
  void vectorWriteCountDelete() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    runA(() -> repository().upsertSourceText(ds, "벡터 본문"));
    if (!supportsVectorSearch()) {
      // 벡터 쓰기·지우기는 꺼진 포트라 예외다.
      assertVectorOff(() -> runA(() -> repository().upsertEmbedding(SPACE, ds, vec())));
      assertVectorOff(
          () -> runA(() -> repository().upsertEmbeddings(SPACE, List.of(ds), List.of(vec()))));
      assertVectorOff(() -> runA(() -> repository().deleteVectors(ds)));
      assertVectorOff(() -> inA(() -> repository().deleteVectorsOf(List.of(ds))));
      // 꺼진 경로 호출이 기반 행을 건드리지 않았다.
      assertThat(fx.embeddingSourceText(ds)).isEqualTo("벡터 본문");
      // 판정은 플랫폼 DB 의 표시 열 SQL 이다(WD-718) — 표시 열을 직접 심어 jOOQ 판과 같은 답인지 본다.
      assertThat(inA(() -> repository().countEmbedded(SPACE))).isZero();
      assertThat(inA(() -> repository().countMissing(SPACE, EXTERNAL))).isEqualTo(1L);
      fx.setEmbeddingMarker(ds, SPACE.dimension().size(), SPACE.model());
      assertThat(inA(() -> repository().countEmbedded(SPACE))).isEqualTo(1L);
      assertThat(inA(() -> repository().countMissing(SPACE, EXTERNAL))).isZero();
      // 다른 모델·다른 차원 공간에서는 아직 없음으로 센다.
      assertThat(
              inA(
                  () ->
                      repository()
                          .countMissing(new EmbeddingSpace(SPACE.dimension(), "x"), EXTERNAL)))
          .isEqualTo(1L);
      assertThat(
              inA(
                  () ->
                      repository()
                          .countMissing(
                              new EmbeddingSpace(EmbeddingDimension.D1536, SPACE.model()),
                              EXTERNAL)))
          .isEqualTo(1L);
      // 다른 테넌트에서는 이 행이 보이지 않는다(정책).
      assertThat(inB(() -> repository().countEmbedded(SPACE))).isZero();
      return;
    }
    assertThat(inA(() -> repository().countMissing(SPACE, EXTERNAL))).isEqualTo(1L);
    runA(() -> repository().upsertEmbedding(SPACE, ds, vec()));
    assertThat(inA(() -> repository().countEmbedded(SPACE))).isEqualTo(1L);
    assertThat(inA(() -> repository().countMissing(SPACE, EXTERNAL))).isZero();
    // 다른 모델 공간에서는 아직 없음으로 센다.
    assertThat(
            inA(
                () ->
                    repository()
                        .countMissing(new EmbeddingSpace(SPACE.dimension(), "x"), EXTERNAL)))
        .isEqualTo(1L);

    runA(() -> repository().deleteVectors(ds));
    assertThat(inA(() -> repository().countEmbedded(SPACE))).isZero();
    // 벡터만 지우고 카탈로그 행은 남긴다.
    assertThat(fx.embeddingSourceText(ds)).isEqualTo("벡터 본문");

    runA(() -> repository().upsertEmbeddings(SPACE, List.of(ds), List.of(vec())));
    assertThat(inA(() -> repository().deleteVectorsOf(List.of(ds)))).isEqualTo(1);
    assertThat(inA(() -> repository().deleteVectorsOf(List.of()))).isZero();
    assertThat(inA(() -> repository().countEmbedded(SPACE))).isZero();
  }

  @Test
  @DisplayName(
      "findMissing 키셋 조회·deleteOtherDimensions — 꺼진 구현은 차원 정리만 예외(VECTOR_SEARCH), 키셋은 표시 열 SQL")
  void findMissing_deleteOtherDimensions() {
    long ds1 = fx.createDataset(tenantA, prefix + " d1");
    long ds2 = fx.createDataset(tenantA, prefix + " d2");
    runA(() -> repository().upsertSourceText(ds1, "본문1"));
    runA(() -> repository().upsertSourceText(ds2, "본문2"));
    long lo = Math.min(ds1, ds2);
    long hi = Math.max(ds1, ds2);
    if (!supportsVectorSearch()) {
      // 차원 정리는 꺼진 포트라 예외다.
      assertVectorOff(() -> inA(() -> repository().deleteOtherDimensions(SPACE.dimension())));
      // 키셋 조회는 표시 열 SQL 이다(WD-718) — jOOQ 판과 같은 순서·상한·판정.
      assertThat(inA(() -> repository().findMissing(SPACE, 0L, 10, EXTERNAL)))
          .extracting(SourceTextRow::datasetId)
          .containsExactly(lo, hi);
      assertThat(inA(() -> repository().findMissing(SPACE, lo, 10, EXTERNAL)))
          .extracting(SourceTextRow::datasetId)
          .containsExactly(hi);
      assertThat(inA(() -> repository().findMissing(SPACE, 0L, 1, EXTERNAL))).hasSize(1);
      fx.setEmbeddingMarker(ds1, SPACE.dimension().size(), SPACE.model());
      assertThat(inA(() -> repository().findMissing(SPACE, 0L, 10, EXTERNAL)))
          .extracting(SourceTextRow::datasetId, SourceTextRow::sourceText)
          .containsExactly(tuple(ds2, "본문2"));
      assertThat(inB(() -> repository().findMissing(SPACE, 0L, 10, EXTERNAL))).isEmpty();
      return;
    }
    assertThat(inA(() -> repository().findMissing(SPACE, 0L, 10, EXTERNAL)))
        .extracting(SourceTextRow::datasetId)
        .containsExactly(lo, hi);
    assertThat(inA(() -> repository().findMissing(SPACE, lo, 10, EXTERNAL)))
        .extracting(SourceTextRow::datasetId)
        .containsExactly(hi);
    assertThat(inA(() -> repository().findMissing(SPACE, 0L, 1, EXTERNAL))).hasSize(1);

    runA(() -> repository().upsertEmbedding(SPACE, ds1, vec()));
    assertThat(inA(() -> repository().findMissing(SPACE, 0L, 10, EXTERNAL)))
        .extracting(SourceTextRow::datasetId)
        .containsExactly(ds2);
    // 현재 차원을 남기면 지울 것이 없고, 다른 차원을 남기면 현재 차원 벡터가 지워진다.
    assertThat(inA(() -> repository().deleteOtherDimensions(SPACE.dimension()))).isZero();
    assertThat(inA(() -> repository().deleteOtherDimensions(EmbeddingDimension.D1536)))
        .isEqualTo(1);
    assertThat(inA(() -> repository().countEmbedded(SPACE))).isZero();
  }
}
