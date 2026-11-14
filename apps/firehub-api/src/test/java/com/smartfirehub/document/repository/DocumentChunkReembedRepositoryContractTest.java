package com.smartfirehub.document.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.repository.DocumentChunkRepository.ChunkContent;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DocumentChunkRepository 재임베딩·검수·진행률 경로 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485, WD-712 에서 두 테넌트 픽스처
 * 기반으로 넓힘). 살아 있는 조회(findChunkContentsByDataset·countChunksInDataset·countAllChunks)는 모든 구현에서 돌고,
 * 벡터 테이블 메서드(스펙 D1)는 {@link #supportsVectorSearch()} 가 거짓이면 {@code UnsupportedOperationException}
 * (VECTOR_SEARCH)을 단언한다.
 */
public abstract class DocumentChunkReembedRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract DocumentChunkRepository repository();

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

  /** 1024 차원 임베딩 벡터 생성(앞 2개만 의미값). */
  private static float[] vec(float a, float b) {
    float[] v = new float[1024];
    v[0] = a;
    v[1] = b;
    return v;
  }

  /** 꺼진 경로 단언 — 예외 종류와 기능 이름. */
  private static void assertVectorOff(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("VECTOR_SEARCH");
  }

  /** 테넌트 A 데이터셋에 완료 문서 하나와 청크 n 개(본문 "c0".."cN")를 살아 있는 경로로 넣고 청크 id 를 오름차순으로 돌려준다. */
  private List<Long> seedChunks(long datasetId, int n) {
    long f =
        fx.createDocumentFile(tenantA, datasetId, prefix + "-" + datasetId + ".txt", "COMPLETED");
    List<Chunk> chunks = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      chunks.add(new Chunk(i, "c" + i, 1));
    }
    runA(() -> repository().insertChunks(f, datasetId, chunks));
    return inA(() -> repository().findChunkContentsByDataset(datasetId)).stream()
        .map(ChunkContent::chunkId)
        .toList();
  }

  /** 등급(ai_policy 지정)을 만들어 데이터셋에 건다. rank 6~8 — 테넌트 기본 등급과 유일 색인이 겹치지 않게. */
  private void setPolicy(long datasetId, int rank, String aiPolicy) {
    long level = fx.createSecurityLevel(tenantA, rank, prefix + "-" + rank);
    fx.setSecurityLevelAiPolicy(level, aiPolicy);
    fx.setDatasetField(datasetId, "security_level_id", level);
  }

  // ---------- 살아 있는 경로 ----------

  @Test
  @DisplayName("findChunkContentsByDataset 은 그 데이터셋 청크만 id 오름차순으로 내고, 없는 데이터셋은 빈 목록이다")
  void findChunkContentsByDataset_orderedAndScoped() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long other = fx.createDataset(tenantA, prefix + " other");
    List<Long> ids = seedChunks(ds, 3);
    seedChunks(other, 2);

    List<ChunkContent> rows = inA(() -> repository().findChunkContentsByDataset(ds));

    assertThat(rows).extracting(ChunkContent::content).containsExactly("c0", "c1", "c2");
    assertThat(rows).extracting(ChunkContent::chunkId).isSortedAccordingTo(Long::compare);
    assertThat(ids).hasSize(3);
    assertThat(inA(() -> repository().findChunkContentsByDataset(-1L))).isEmpty();
  }

  @Test
  @DisplayName("countChunksInDataset 은 그 데이터셋 청크만 중복 없이 세고, 다른 데이터셋·없는 id 는 세지 않으며 빈 목록은 0 이다")
  void countChunksInDataset_scopedAndDistinct() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long other = fx.createDataset(tenantA, prefix + " other");
    List<Long> ids = seedChunks(ds, 3);
    List<Long> otherIds = seedChunks(other, 2);

    List<Long> asked = new ArrayList<>(ids);
    asked.add(ids.get(0)); // 중복
    asked.addAll(otherIds);
    asked.add(-1L);

    assertThat(inA(() -> repository().countChunksInDataset(ds, asked))).isEqualTo(3L);
    assertThat(inA(() -> repository().countChunksInDataset(other, asked))).isEqualTo(2L);
    assertThat(inA(() -> repository().countChunksInDataset(ds, List.of()))).isZero();
  }

  @Test
  @DisplayName("countChunksInDataset 은 1000개를 넘는 id 목록(1101개, 중복 포함)도 처리한다")
  void countChunksInDataset_moreThanThousandIds() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    List<Long> ids = seedChunks(ds, 3);
    List<Long> asked = new ArrayList<>();
    for (long i = 1; i <= 1095; i++) {
      asked.add(-i); // 없는 id
    }
    asked.addAll(ids);
    asked.addAll(ids); // 중복 3개

    assertThat(asked).hasSize(1101);
    assertThat(inA(() -> repository().countChunksInDataset(ds, asked))).isEqualTo(3L);
  }

  @Test
  @DisplayName(
      "countAllChunks 는 등급 ai_policy 와 호스팅으로 모집단을 거른다(ALL 은 둘 다, SELF_HOSTED_ONLY 는 자체 호스팅만, DENY 는 없음)")
  void countAllChunks_aiPolicyMatrix() {
    long dsAll = fx.createDataset(tenantA, prefix + " all");
    long dsSelf = fx.createDataset(tenantA, prefix + " self");
    long dsDeny = fx.createDataset(tenantA, prefix + " deny");
    setPolicy(dsAll, 6, "ALL");
    setPolicy(dsSelf, 7, "SELF_HOSTED_ONLY");
    setPolicy(dsDeny, 8, "DENY");
    seedChunks(dsAll, 2);
    seedChunks(dsSelf, 3);
    seedChunks(dsDeny, 4);

    assertThat(inA(() -> repository().countAllChunks(EXTERNAL))).isEqualTo(2L);
    assertThat(inA(() -> repository().countAllChunks(SELF_HOSTED))).isEqualTo(5L);
  }

  // ---------- 벡터 테이블 메서드(스펙 D1) ----------

  @Test
  @DisplayName(
      "upsertEmbeddings·countEmbedded·countMissing·deleteVectorsOf — 꺼진 구현은 쓰기·지우기만 예외(VECTOR_SEARCH), 판정은 표시 열 SQL")
  void vectorWriteCountDelete() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    setPolicy(ds, 6, "ALL");
    List<Long> ids = seedChunks(ds, 2);
    List<float[]> embeddings = List.of(vec(0.5f, 0.5f), vec(0.25f, 0.75f));
    if (!supportsVectorSearch()) {
      // 벡터 쓰기·지우기는 꺼진 포트라 예외다.
      assertVectorOff(() -> runA(() -> repository().upsertEmbeddings(SPACE, ids, embeddings)));
      assertVectorOff(() -> inA(() -> repository().deleteVectorsOf(List.of(ds))));
      // 꺼진 경로 호출이 기반 행을 건드리지 않았다.
      assertThat(inA(() -> repository().countChunksInDataset(ds, ids))).isEqualTo(2L);
      // 판정은 플랫폼 DB 의 표시 열 SQL 이다(WD-718) — 표시 열을 직접 심어 jOOQ 판과 같은 답인지 본다.
      assertThat(inA(() -> repository().countEmbedded(SPACE))).isZero();
      assertThat(inA(() -> repository().countMissing(SPACE, EXTERNAL))).isEqualTo(2L);
      for (long id : ids) {
        fx.setChunkMarker(id, SPACE.dimension().size(), SPACE.model());
      }
      assertThat(inA(() -> repository().countEmbedded(SPACE))).isEqualTo(2L);
      assertThat(inA(() -> repository().countMissing(SPACE, EXTERNAL))).isZero();
      assertThat(
              inA(
                  () ->
                      repository()
                          .countMissing(new EmbeddingSpace(SPACE.dimension(), "x"), EXTERNAL)))
          .isEqualTo(2L);
      // 다른 테넌트에서는 이 청크들이 보이지 않는다(정책).
      assertThat(inB(() -> repository().countEmbedded(SPACE))).isZero();
      return;
    }
    assertThat(inA(() -> repository().countMissing(SPACE, EXTERNAL))).isEqualTo(2L);
    runA(() -> repository().upsertEmbeddings(SPACE, ids, embeddings));
    assertThat(inA(() -> repository().countEmbedded(SPACE))).isEqualTo(2L);
    assertThat(inA(() -> repository().countMissing(SPACE, EXTERNAL))).isZero();
    // 다른 모델 공간에서는 아직 없음으로 센다.
    assertThat(
            inA(
                () ->
                    repository()
                        .countMissing(new EmbeddingSpace(SPACE.dimension(), "x"), EXTERNAL)))
        .isEqualTo(2L);

    assertThat(inA(() -> repository().deleteVectorsOf(List.of(ds)))).isEqualTo(2);
    assertThat(inA(() -> repository().deleteVectorsOf(List.of()))).isZero();
    assertThat(inA(() -> repository().countEmbedded(SPACE))).isZero();
    // 벡터만 지우고 청크 본문은 남긴다.
    assertThat(inA(() -> repository().countChunksInDataset(ds, ids))).isEqualTo(2L);
  }

  @Test
  @DisplayName(
      "findMissing 키셋 조회·deleteOtherDimensions — 꺼진 구현은 차원 정리만 예외(VECTOR_SEARCH), 키셋은 표시 열 SQL")
  void findMissing_deleteOtherDimensions() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    setPolicy(ds, 6, "ALL");
    List<Long> ids = seedChunks(ds, 2);
    long lo = ids.get(0);
    long hi = ids.get(1);
    if (!supportsVectorSearch()) {
      // 차원 정리는 꺼진 포트라 예외다.
      assertVectorOff(() -> inA(() -> repository().deleteOtherDimensions(SPACE.dimension())));
      // 키셋 조회는 표시 열 SQL 이다(WD-718) — jOOQ 판과 같은 순서·상한·판정.
      assertThat(inA(() -> repository().findMissing(SPACE, 0L, 10, EXTERNAL)))
          .extracting(ChunkContent::chunkId)
          .containsExactly(lo, hi);
      assertThat(inA(() -> repository().findMissing(SPACE, lo, 10, EXTERNAL)))
          .extracting(ChunkContent::chunkId)
          .containsExactly(hi);
      assertThat(inA(() -> repository().findMissing(SPACE, 0L, 1, EXTERNAL))).hasSize(1);
      fx.setChunkMarker(lo, SPACE.dimension().size(), SPACE.model());
      assertThat(inA(() -> repository().findMissing(SPACE, 0L, 10, EXTERNAL)))
          .extracting(ChunkContent::chunkId)
          .containsExactly(hi);
      assertThat(inB(() -> repository().findMissing(SPACE, 0L, 10, EXTERNAL))).isEmpty();
      return;
    }
    assertThat(inA(() -> repository().findMissing(SPACE, 0L, 10, EXTERNAL)))
        .extracting(ChunkContent::chunkId)
        .containsExactly(lo, hi);
    assertThat(inA(() -> repository().findMissing(SPACE, lo, 10, EXTERNAL)))
        .extracting(ChunkContent::chunkId)
        .containsExactly(hi);
    assertThat(inA(() -> repository().findMissing(SPACE, 0L, 1, EXTERNAL))).hasSize(1);

    runA(() -> repository().upsertEmbeddings(SPACE, List.of(lo), List.of(vec(1f, 0f))));
    assertThat(inA(() -> repository().findMissing(SPACE, 0L, 10, EXTERNAL)))
        .extracting(ChunkContent::chunkId)
        .containsExactly(hi);
    // 현재 차원을 남기면 지울 것이 없고, 다른 차원을 남기면 현재 차원 벡터가 지워진다.
    assertThat(inA(() -> repository().deleteOtherDimensions(SPACE.dimension()))).isZero();
    assertThat(inA(() -> repository().deleteOtherDimensions(EmbeddingDimension.D1536)))
        .isEqualTo(1);
    assertThat(inA(() -> repository().countEmbedded(SPACE))).isZero();
  }
}
