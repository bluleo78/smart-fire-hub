package com.smartfirehub.vectorstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SearchCandidateRepository} 계약 테스트(WD-720, ADR 0002 결정 5 가시성 2단계) — 구현마다 하위 클래스로 실행한다. 두
 * 테넌트를 만들어 RLS 가 다른 테넌트 행을 가리는지, 가시성·저장 유형·요청 데이터셋·문서 상태·공간 조건이 pgvector 검색 SQL 과 같은 답을 내는지 고정한다.
 */
public abstract class SearchCandidateRepositoryContractTest extends TenantPairContractBase {

  private static final EmbeddingSpace SPACE =
      new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");

  protected abstract SearchCandidateRepository repository();

  /** 낮은 등급(rank 1)과 높은 등급(rank 9) 데이터셋, 문서 데이터셋, 다른 테넌트 데이터셋. */
  private record Seed(long user, long open, long secret, long doc, long other) {}

  private Seed seed() {
    long user = fx.createUser(prefix + "u", prefix + "@example.com");
    long low = fx.createSecurityLevel(tenantA, 1, prefix + "낮음");
    long high = fx.createSecurityLevel(tenantA, 9, prefix + "높음");
    long open = fx.createDataset(tenantA, user, prefix + "_open", low);
    long secret = fx.createDataset(tenantA, user, prefix + "_secret", high);
    long doc = fx.createDataset(tenantA, user, prefix + "_doc", low);
    fx.setDatasetField(doc, "storage_type", "DOCUMENT");
    long other = fx.createDataset(tenantB, prefix + " 남의 것");
    return new Seed(user, open, secret, doc, other);
  }

  /** rank 5 까지 보는 조회자(높은 등급 데이터셋은 못 본다). */
  private DatasetVisibility rank5(Seed s) {
    return new DatasetVisibility(
        new Clearance(s.user(), tenantA, 5, Set.of(), false, Set.of()), null);
  }

  @Test
  @DisplayName("볼 수 있는 데이터셋 id — 등급·저장 유형·요청 데이터셋으로 좁히고 다른 테넌트는 RLS 가 가린다")
  void visibleDatasetIds() {
    Seed s = seed();
    DatasetVisibility all = DatasetVisibility.UNRESTRICTED;

    assertThat(inA(() -> repository().findVisibleDatasetIds(all, null, null)))
        .contains(s.open(), s.secret(), s.doc())
        .doesNotContain(s.other());
    assertThat(inA(() -> repository().findVisibleDatasetIds(rank5(s), null, null)))
        .contains(s.open(), s.doc())
        .doesNotContain(s.secret(), s.other());
    assertThat(inA(() -> repository().findVisibleDatasetIds(all, "DOCUMENT", null)))
        .containsExactly(s.doc());
    // 요청 데이터셋으로 좁힌다 — 다른 테넌트 id 를 넣어도 나오지 않는다.
    assertThat(
            inA(
                () ->
                    repository().findVisibleDatasetIds(all, null, List.of(s.secret(), s.other()))))
        .containsExactly(s.secret());
    assertThat(inA(() -> repository().findVisibleDatasetIds(all, null, List.of())))
        .hasSizeGreaterThan(2);
  }

  @Test
  @DisplayName("후보 데이터셋 확인 — 가시성·저장 유형·공간(표시 열) 조건과 표시 필드, 다른 테넌트 후보는 빠진다")
  void datasetHits() {
    Seed s = seed();
    long category = fx.createCategory(tenantA, prefix + " 분류");
    fx.assignCategory(s.open(), category);
    fx.setDatasetField(s.open(), "description", "열린 설명");
    fx.seedEmbeddingRow(tenantA, s.open(), "열린");
    fx.seedEmbeddingRow(tenantA, s.secret(), "비밀");
    fx.seedEmbeddingRow(tenantA, s.doc(), "문서");
    fx.seedEmbeddingRow(tenantB, s.other(), "남의 것");
    fx.setEmbeddingMarker(s.open(), SPACE.dimension().size(), SPACE.model());
    List<Long> candidates = List.of(s.open(), s.secret(), s.doc(), s.other());

    // 키워드(공간 없음): 가시성만 — 높은 등급·다른 테넌트가 빠진다. 표시 필드가 채워지고 점수는 0.
    List<DatasetSearchHit> keyword =
        inA(() -> repository().findDatasetHits(candidates, rank5(s), null, null));
    assertThat(keyword)
        .extracting(DatasetSearchHit::datasetId)
        .containsExactlyInAnyOrder(s.open(), s.doc());
    DatasetSearchHit open =
        keyword.stream().filter(h -> h.datasetId() == s.open()).findFirst().orElseThrow();
    assertThat(open.category()).isEqualTo(prefix + " 분류");
    assertThat(open.description()).isEqualTo("열린 설명");
    assertThat(open.storageType()).isEqualTo("TABLE");
    assertThat(open.score()).isZero();

    // 저장 유형 조건.
    assertThat(inA(() -> repository().findDatasetHits(candidates, rank5(s), "DOCUMENT", null)))
        .extracting(DatasetSearchHit::datasetId)
        .containsExactly(s.doc());
    // 의미(공간 있음): 표시 열이 그 공간을 가리키는 행만 — 다른 모델이면 없다.
    assertThat(inA(() -> repository().findDatasetHits(candidates, rank5(s), null, SPACE)))
        .extracting(DatasetSearchHit::datasetId)
        .containsExactly(s.open());
    assertThat(
            inA(
                () ->
                    repository()
                        .findDatasetHits(
                            candidates,
                            rank5(s),
                            null,
                            new EmbeddingSpace(SPACE.dimension(), "other-model"))))
        .isEmpty();
    // 다른 테넌트에서는 A 의 후보가 하나도 보이지 않는다.
    assertThat(
            inB(
                () ->
                    repository()
                        .findDatasetHits(candidates, DatasetVisibility.UNRESTRICTED, null, null)))
        .extracting(DatasetSearchHit::datasetId)
        .containsExactly(s.other());
    assertThat(inA(() -> repository().findDatasetHits(List.of(), rank5(s), null, null))).isEmpty();
  }

  @Test
  @DisplayName("후보 청크 확인 — 완료된 문서만, 가시성·요청 데이터셋·공간 조건과 표시 필드, 다른 테넌트 청크는 빠진다")
  void chunkHits() {
    Seed s = seed();
    long done = fx.createDocumentFile(tenantA, s.doc(), "done.txt", "COMPLETED");
    long parsing = fx.createDocumentFile(tenantA, s.doc(), "parsing.txt", "PARSING");
    long secretFile = fx.createDocumentFile(tenantA, s.secret(), "secret.txt", "COMPLETED");
    long otherFile = fx.createDocumentFile(tenantB, s.other(), "b.txt", "COMPLETED");
    long c1 = fx.insertChunk(tenantA, done, s.doc(), 0, "완료 청크");
    long c2 = fx.insertChunk(tenantA, parsing, s.doc(), 0, "적재 중 청크");
    long c3 = fx.insertChunk(tenantA, secretFile, s.secret(), 0, "비밀 청크");
    long c4 = fx.insertChunk(tenantB, otherFile, s.other(), 0, "남의 청크");
    fx.setChunkMarker(c1, SPACE.dimension().size(), SPACE.model());
    List<Long> candidates = List.of(c1, c2, c3, c4);

    List<DocumentSearchHit> keyword =
        inA(() -> repository().findChunkHits(candidates, rank5(s), null, null));
    assertThat(keyword).extracting(DocumentSearchHit::chunkId).containsExactly(c1);
    DocumentSearchHit hit = keyword.get(0);
    assertThat(hit.fileName()).isEqualTo("done.txt");
    assertThat(hit.content()).isEqualTo("완료 청크");
    assertThat(hit.datasetId()).isEqualTo(s.doc());
    assertThat(hit.documentFileId()).isEqualTo(done);
    assertThat(hit.chunkIndex()).isZero();
    assertThat(hit.score()).isZero();

    // 제한 없는 가시성이면 높은 등급 청크도 보인다(완료된 문서만).
    assertThat(
            inA(
                () ->
                    repository()
                        .findChunkHits(candidates, DatasetVisibility.UNRESTRICTED, null, null)))
        .extracting(DocumentSearchHit::chunkId)
        .containsExactlyInAnyOrder(c1, c3);
    // 요청 데이터셋으로 좁힌다.
    assertThat(
            inA(
                () ->
                    repository()
                        .findChunkHits(
                            candidates, DatasetVisibility.UNRESTRICTED, List.of(s.secret()), null)))
        .extracting(DocumentSearchHit::chunkId)
        .containsExactly(c3);
    // 의미(공간 있음): 표시 열이 그 공간인 청크만.
    assertThat(
            inA(
                () ->
                    repository()
                        .findChunkHits(candidates, DatasetVisibility.UNRESTRICTED, null, SPACE)))
        .extracting(DocumentSearchHit::chunkId)
        .containsExactly(c1);
    // 다른 테넌트.
    assertThat(
            inB(
                () ->
                    repository()
                        .findChunkHits(candidates, DatasetVisibility.UNRESTRICTED, null, null)))
        .extracting(DocumentSearchHit::chunkId)
        .containsExactly(c4);
  }
}
