package com.smartfirehub.document.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.SourceHash;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
import com.smartfirehub.support.ChunkSeeds;
import com.smartfirehub.user.repository.AccessFixtures.ChunkRow;
import com.smartfirehub.user.repository.TenantPairContractBase;
import com.smartfirehub.vectorstore.ChunkVectorIndex;
import com.smartfirehub.vectorstore.off.OffChunkVectorIndex;
import com.smartfirehub.vectorstore.repository.jooq.PgChunkVectorIndex;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * DocumentChunkRepository 적재·삭제·검색 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485, WD-712 에서 두 테넌트 픽스처 기반으로 넓힘).
 * document_chunk 는 테넌트 정책 아래 있어 호출은 {@code inA}/{@code inB} 로 감싼다.
 *
 * <p>꺼진 기능 경로(스펙 D1)는 훅으로 가른다: {@link #supportsVectorSearch()}(insertBatch·searchByCosine) 와 {@link
 * #supportsTrigramSearch()}(searchByTrigram) 가 참(jOOQ/PG)이면 리포지토리 자신의 메서드로 의미를 확인하고, 거짓(MyBatis —
 * PG 프로필·Tibero 모두)이면 {@code UnsupportedOperationException} 과 기능 이름을 단언한다. 벡터 저장소의 깊은 동작(차원 이동·HNSW
 * 계획)은 {@code DocumentChunkVectorStoreTest} 가 고정한다.
 */
public abstract class DocumentChunkRepositoryContractTest extends TenantPairContractBase {

  /** 모든 적재·검색이 쓰는 임베딩 공간(1024 차원 테이블 + bge-m3 모델). */
  private static final EmbeddingSpace SPACE =
      new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");

  // 제한 없는 가시성 — 이 테스트는 등급 가시성이 아니라 검색 자체를 본다(가시성은 securitylevel 패키지 테스트가 고정한다).
  private static final DatasetVisibility ALL_VISIBLE = DatasetVisibility.UNRESTRICTED;

  private static final EmbeddingAllowance SELF_HOSTED =
      new EmbeddingAllowance(ProviderHosting.SELF_HOSTED);

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract DocumentChunkRepository repository();

  // 검색은 리포지토리에서 벡터 저장소 포트로 나갔다(ADR 0002 결정 2). PG 컨텍스트에서만 있는 빈이라 선택 주입이다.
  @Autowired(required = false)
  private PgChunkVectorIndex pgIndex;

  /**
   * 검색 시나리오가 쓰는 청크 색인 포트. 벡터 검색을 지원하는 구현(jOOQ)이면 pgvector 구현, 아니면 꺼짐 구현이다. 꺼짐 구현은 DB 에 닿지 않고 예전
   * MyBatis 검색 메서드와 같은 예외를 던진다.
   */
  protected ChunkVectorIndex searchIndex() {
    return supportsVectorSearch() ? pgIndex : new OffChunkVectorIndex();
  }

  /** 벡터 테이블 메서드(insertBatch·searchByCosine)를 구현하는가(jOOQ 참, MyBatis 거짓 — 스펙 D1). */
  protected boolean supportsVectorSearch() {
    return true;
  }

  /** 트라이그램 검색(searchByTrigram)을 구현하는가(jOOQ 참, MyBatis 거짓 — 스펙 D1). */
  protected boolean supportsTrigramSearch() {
    return true;
  }

  private static float[] vec(float a, float b) {
    float[] v = new float[1024];
    v[0] = a;
    v[1] = b;
    return v;
  }

  /** 꺼진 경로 단언 — 예외 종류와 기능 이름. */
  private static void assertOff(String feature, ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(feature);
  }

  /** 테넌트 A 에 데이터셋 하나를 만든다. */
  private long dataset(String name) {
    return fx.createDataset(tenantA, prefix + " " + name);
  }

  /** 테넌트 A 데이터셋에 문서 파일 하나를 만든다. */
  private long file(long datasetId, String name, String status) {
    return fx.createDocumentFile(tenantA, datasetId, name, status);
  }

  /** 결과 없는 호출을 테넌트 B 범위에서 실행한다. */
  private void runB(Runnable call) {
    inB(
        () -> {
          call.run();
          return null;
        });
  }

  // ---------- 살아 있는 경로 ----------

  @Test
  @DisplayName(
      "청크 키워드 표시(WD-720) — insertChunks 가 돌려준 id 가 청크 순서이고, 표시가 없거나 규칙·본문이 어긋난 청크만 백로그다. 다른 테넌트는 보지 못한다")
  void keywordMarker_roundTrip() {
    long ds = dataset("kw");
    long f = file(ds, "kw.txt", "COMPLETED");
    List<Long> ids =
        inA(
            () ->
                repository()
                    .insertChunks(
                        f, ds, List.of(new Chunk(0, "소방 점검", 1), new Chunk(1, "화재 예방", 1))));
    assertThat(ids).hasSize(2).doesNotHaveDuplicates();
    assertThat(inA(() -> repository().findKeywordMissing("r1", 0L, 10)))
        .extracting(
            DocumentChunkRepository.ChunkSource::chunkId,
            DocumentChunkRepository.ChunkSource::content)
        .containsExactly(tuple(ids.get(0), "소방 점검"), tuple(ids.get(1), "화재 예방"));

    // 첫 청크는 맞는 본문 해시로, 둘째는 옛 본문 해시로(늦은 쓰기) 표시를 채운다 — 둘째는 채워지지 않는다.
    runA(
        () ->
            repository()
                .markKeywordIndexed(
                    "r1", ids, List.of(SourceHash.of("소방 점검"), SourceHash.of("옛 본문"))));
    assertThat(inA(() -> repository().findKeywordMissing("r1", 0L, 10)))
        .extracting(DocumentChunkRepository.ChunkSource::chunkId)
        .containsExactly(ids.get(1));
    // 규칙이 바뀌면 다시 백로그다.
    assertThat(inA(() -> repository().findKeywordMissing("r2", 0L, 10))).hasSize(2);
    // 키셋·상한.
    assertThat(inA(() -> repository().findKeywordMissing("r2", ids.get(0), 10))).hasSize(1);
    assertThat(inA(() -> repository().findKeywordMissing("r2", 0L, 1))).hasSize(1);
    // 다른 테넌트에서는 보이지 않는다.
    assertThat(inB(() -> repository().findKeywordMissing("r2", 0L, 10))).isEmpty();
  }

  @Test
  @DisplayName("insertChunksOnly 는 순번·본문·토큰 수를 그대로 넣고 tenant_id 는 컨텍스트가 채운다")
  void insertChunksOnly_roundTrip() {
    long ds = dataset("ds");
    long f = file(ds, "a.txt", "COMPLETED");

    runA(
        () ->
            repository()
                .insertChunks(
                    f,
                    ds,
                    List.of(
                        new Chunk(0, "첫 청크 First", 3),
                        new Chunk(1, "두 번째 청크", 2),
                        new Chunk(2, "third", 1))));

    assertThat(fx.chunkRows(f))
        .containsExactly(
            new ChunkRow(ds, 0, "첫 청크 First", 3, tenantA),
            new ChunkRow(ds, 1, "두 번째 청크", 2, tenantA),
            new ChunkRow(ds, 2, "third", 1, tenantA));
  }

  @Test
  @DisplayName("insertChunksOnly 는 한 묶음(200건)을 넘는 청크도 모두 넣고, 빈 목록은 아무것도 하지 않는다")
  void insertChunksOnly_acrossBatches_emptyIsNoop() {
    long ds = dataset("ds");
    long f = file(ds, "b.txt", "COMPLETED");
    List<Chunk> chunks = new ArrayList<>();
    for (int i = 0; i < 401; i++) {
      chunks.add(new Chunk(i, "청크 c" + i, 1));
    }

    runA(() -> repository().insertChunks(f, ds, chunks));
    runA(() -> repository().insertChunks(f, ds, List.of()));

    List<ChunkRow> rows = fx.chunkRows(f);
    assertThat(rows).hasSize(401);
    assertThat(rows.get(400)).isEqualTo(new ChunkRow(ds, 400, "청크 c400", 1, tenantA));
  }

  @Test
  @DisplayName("insertChunksOnly 는 Tibero VARCHAR2(65532) 바이트 한도 가까운 한글 본문도 그대로 저장한다")
  void insertChunksOnly_longKoreanContent() {
    long ds = dataset("ds");
    long f = file(ds, "long.txt", "COMPLETED");
    // 한글 3바이트 × 21,000 + ASCII 2,000 = 65,000바이트 — 한도(65,532) 안, SQL 식 4000바이트 한도는 훨씬 넘는다.
    String text = "화재통계".repeat(5250) + "ab".repeat(1000);
    assertThat(text.getBytes(StandardCharsets.UTF_8).length).isEqualTo(65_000);

    runA(
        () ->
            repository()
                .insertChunks(f, ds, List.of(new Chunk(0, text, 9), new Chunk(1, "짧은", 1))));

    List<ChunkRow> rows = fx.chunkRows(f);
    assertThat(rows.get(0).content()).isEqualTo(text);
    assertThat(rows.get(1).content()).isEqualTo("짧은");
    // 매퍼 읽기 경로(findChunkContentsByDataset)로도 한도 근처 본문이 그대로 돌아온다.
    assertThat(inA(() -> repository().findChunkContentsByDataset(ds)))
        .extracting(DocumentChunkRepository.ChunkContent::content)
        .containsExactly(text, "짧은");
  }

  @Test
  @DisplayName("빈 본문 — PG 는 빈 문자열로 저장하고, Tibero 는 NULL 이 되어 NOT NULL 위반으로 거절된다")
  void insertChunksOnly_emptyContent() {
    long ds = dataset("ds");
    long f = file(ds, "e.txt", "COMPLETED");
    if (emptyStringIsNull()) {
      assertThatThrownBy(
              () -> runA(() -> repository().insertChunks(f, ds, List.of(new Chunk(0, "", 0)))))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
      assertThat(fx.chunkRows(f)).isEmpty();
    } else {
      runA(() -> repository().insertChunks(f, ds, List.of(new Chunk(0, "", 0))));
      assertThat(fx.chunkRows(f)).extracting(ChunkRow::content).containsExactly("");
    }
  }

  @Test
  @DisplayName("deleteByDocumentFileId 는 그 문서의 청크만 지우고, 없는 문서 id 는 예외 없이 넘어간다")
  void deleteByDocumentFileId_removesOnlyThatFile() {
    long ds = dataset("ds");
    long f1 = file(ds, "1.txt", "COMPLETED");
    long f2 = file(ds, "2.txt", "COMPLETED");
    runA(
        () ->
            repository().insertChunks(f1, ds, List.of(new Chunk(0, "a", 1), new Chunk(1, "b", 1))));
    runA(() -> repository().insertChunks(f2, ds, List.of(new Chunk(0, "keep", 1))));

    runA(() -> repository().deleteByDocumentFileId(f1));
    runA(() -> repository().deleteByDocumentFileId(f1));
    runA(() -> repository().deleteByDocumentFileId(-1L));

    assertThat(fx.chunkRows(f1)).isEmpty();
    assertThat(fx.chunkRows(f2)).extracting(ChunkRow::content).containsExactly("keep");
  }

  @Test
  @DisplayName("다른 테넌트 차단 — B 컨텍스트에서는 A 청크를 읽거나 세지 못하고 지우지도 못한다")
  void otherTenant_isBlocked() {
    long ds = dataset("ds");
    long f = file(ds, "a.txt", "COMPLETED");
    runA(
        () ->
            repository().insertChunks(f, ds, List.of(new Chunk(0, "a", 1), new Chunk(1, "b", 1))));
    List<Long> ids =
        inA(() -> repository().findChunkContentsByDataset(ds)).stream()
            .map(DocumentChunkRepository.ChunkContent::chunkId)
            .toList();

    // 허용 등급(ALL)을 걸어 A 에서는 countAllChunks 가 실제로 세는 상태를 만든다 — B 의 0 이 술어 탓이 아님을 보이려고.
    long level = fx.createSecurityLevel(tenantA, 6, prefix + "-all");
    fx.setSecurityLevelAiPolicy(level, "ALL");
    fx.setDatasetField(ds, "security_level_id", level);
    assertThat(inA(() -> repository().countAllChunks(SELF_HOSTED))).isEqualTo(2L);

    assertThat(inB(() -> repository().findChunkContentsByDataset(ds))).isEmpty();
    assertThat(inB(() -> repository().countChunksInDataset(ds, ids))).isZero();
    assertThat(inB(() -> repository().countAllChunks(SELF_HOSTED))).isZero();
    runB(() -> repository().deleteByDocumentFileId(f));

    assertThat(fx.chunkRows(f)).hasSize(2);
    assertThat(inA(() -> repository().countChunksInDataset(ds, ids))).isEqualTo(2L);
  }

  // ---------- 벡터 경로(스펙 D1) ----------

  @Test
  @DisplayName(
      "insertBatch + searchByCosine 은 가까운 청크를 먼저 낸다 — 꺼진 구현은 VECTOR_SEARCH 예외이고 행을 남기지 않는다")
  void insertBatch_cosineRanksNearestFirst() {
    long ds = dataset("ds");
    long f = file(ds, "doc.txt", "COMPLETED");
    List<Chunk> chunks = List.of(new Chunk(0, "near", 1), new Chunk(1, "far", 1));
    List<float[]> embeddings = List.of(vec(1f, 0f), vec(0f, 1f));
    if (!supportsVectorSearch()) {
      assertOff(
          "VECTOR_SEARCH",
          () ->
              runA(
                  () ->
                      ChunkSeeds.insertWithVectors(
                          repository(), f, ds, chunks, embeddings, SPACE)));
      assertOff(
          "VECTOR_SEARCH",
          () -> inA(() -> searchIndex().semantic(SPACE, vec(1f, 0f), List.of(ds), 5, ALL_VISIBLE)));
      assertThat(fx.chunkRows(f)).isEmpty();
      return;
    }
    runA(() -> ChunkSeeds.insertWithVectors(repository(), f, ds, chunks, embeddings, SPACE));

    List<DocumentSearchHit> hits =
        inA(() -> searchIndex().semantic(SPACE, vec(1f, 0f), List.of(ds), 5, ALL_VISIBLE));

    assertThat(hits).isNotEmpty();
    assertThat(hits.get(0).content()).isEqualTo("near");
    assertThat(hits.get(0).fileName()).isEqualTo("doc.txt");
    assertThat(hits.get(0).score()).isGreaterThan(hits.get(hits.size() - 1).score());
    assertThat(fx.chunkRows(f)).extracting(ChunkRow::content).containsExactly("near", "far");
  }

  @Test
  @DisplayName("insertBatch 는 한 묶음(200건)을 넘는 청크도 모두 넣는다 — 꺼진 구현은 VECTOR_SEARCH 예외이고 행을 남기지 않는다")
  void insertBatch_acrossBatches() {
    long ds = dataset("ds");
    long f = file(ds, "b.txt", "COMPLETED");
    List<Chunk> chunks = new ArrayList<>();
    List<float[]> embeddings = new ArrayList<>();
    for (int i = 0; i < 201; i++) {
      chunks.add(new Chunk(i, "c" + i, 1));
      embeddings.add(vec(1f, 0f));
    }
    if (!supportsVectorSearch()) {
      assertOff(
          "VECTOR_SEARCH",
          () ->
              runA(
                  () ->
                      ChunkSeeds.insertWithVectors(
                          repository(), f, ds, chunks, embeddings, SPACE)));
      assertThat(fx.chunkRows(f)).isEmpty();
      return;
    }

    runA(() -> ChunkSeeds.insertWithVectors(repository(), f, ds, chunks, embeddings, SPACE));

    assertThat(fx.chunkRows(f)).hasSize(201);
  }

  @Test
  @DisplayName("searchByCosine 은 완료되지 않은 문서의 청크를 뺀다 — 꺼진 구현은 VECTOR_SEARCH 예외")
  void searchByCosine_excludesNonCompleted() {
    long ds = dataset("ds");
    long f = file(ds, "p.txt", "PARSING");
    if (!supportsVectorSearch()) {
      // 시드(벡터 쓰기)도 꺼진 경로라 본문만 살아 있는 경로로 넣고 검색이 예외인지 본다.
      runA(() -> repository().insertChunks(f, ds, List.of(new Chunk(0, "hidden", 1))));
      assertOff(
          "VECTOR_SEARCH",
          () -> inA(() -> searchIndex().semantic(SPACE, vec(1f, 0f), List.of(ds), 5, ALL_VISIBLE)));
      return;
    }
    runA(
        () ->
            ChunkSeeds.insertWithVectors(
                repository(),
                f,
                ds,
                List.of(new Chunk(0, "hidden", 1)),
                List.of(vec(1f, 0f)),
                SPACE));

    assertThat(inA(() -> searchIndex().semantic(SPACE, vec(1f, 0f), List.of(ds), 5, ALL_VISIBLE)))
        .isEmpty();
  }

  // ---------- 트라이그램 경로(스펙 D1) ----------

  @Test
  @DisplayName("searchByTrigram 은 정확한 식별자가 든 청크를 찾는다 — 꺼진 구현은 TRIGRAM_SEARCH 예외")
  void searchByTrigram_findsExactTerm() {
    long ds = dataset("ds");
    long f = file(ds, "t.txt", "COMPLETED");
    // 고유 식별자가 든 청크와 무관한 청크 — 키워드 검색은 식별자 청크를 찾아야 한다. 적재는 벡터가 필요 없는 살아 있는 경로로 한다.
    runA(
        () ->
            repository()
                .insertChunks(
                    f,
                    ds,
                    List.of(
                        new Chunk(0, "재난번호 UR4206974320 강릉 산불 피해", 1),
                        new Chunk(1, "전혀 무관한 일반 텍스트입니다", 1))));
    if (!supportsTrigramSearch()) {
      assertOff(
          "TRIGRAM_SEARCH",
          () -> inA(() -> searchIndex().keyword("UR4206974320", List.of(ds), 5, ALL_VISIBLE)));
      return;
    }

    List<DocumentSearchHit> hits =
        inA(() -> searchIndex().keyword("UR4206974320", List.of(ds), 5, ALL_VISIBLE));

    assertThat(hits).isNotEmpty();
    assertThat(hits.get(0).content()).contains("UR4206974320");
  }

  @Test
  @DisplayName(
      "searchByTrigram 은 기본 임계값(0.6) 아래 중간 유사도(약 0.13)도 낸다 — SET LOCAL 0.1 보존, 꺼진 구현은 TRIGRAM_SEARCH 예외")
  void searchByTrigram_moderateSimilarity() {
    // word_similarity('fire statistics report', 'annual wildfire damage summary 2026') ≈ 0.133.
    // searchByTrigram 이 SET LOCAL 로 임계값을 0.1 로 낮춰야만 %> 가 이 청크를 통과시킨다(GUC 보존 회귀를 잡는다).
    long ds = dataset("ds");
    long f = file(ds, "m.txt", "COMPLETED");
    runA(
        () ->
            repository()
                .insertChunks(
                    f, ds, List.of(new Chunk(0, "annual wildfire damage summary 2026", 1))));
    if (!supportsTrigramSearch()) {
      assertOff(
          "TRIGRAM_SEARCH",
          () ->
              inA(
                  () ->
                      searchIndex()
                          .keyword("fire statistics report", List.of(ds), 5, ALL_VISIBLE)));
      return;
    }

    List<DocumentSearchHit> hits =
        inA(() -> searchIndex().keyword("fire statistics report", List.of(ds), 5, ALL_VISIBLE));

    assertThat(hits).isNotEmpty();
    assertThat(hits.get(0).content()).isEqualTo("annual wildfire damage summary 2026");
  }

  @Test
  @DisplayName("searchByTrigram 은 완료되지 않은 문서의 청크를 뺀다 — 꺼진 구현은 TRIGRAM_SEARCH 예외")
  void searchByTrigram_excludesNonCompleted() {
    long ds = dataset("ds");
    long f = file(ds, "pp.txt", "PARSING");
    runA(() -> repository().insertChunks(f, ds, List.of(new Chunk(0, "UR4206974320 숨김", 1))));
    if (!supportsTrigramSearch()) {
      assertOff(
          "TRIGRAM_SEARCH",
          () -> inA(() -> searchIndex().keyword("UR4206974320", List.of(ds), 5, ALL_VISIBLE)));
      return;
    }

    assertThat(inA(() -> searchIndex().keyword("UR4206974320", List.of(ds), 5, ALL_VISIBLE)))
        .isEmpty();
  }
}
