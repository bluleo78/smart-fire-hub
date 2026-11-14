package com.smartfirehub.vectorstore;

import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.util.Collection;
import java.util.List;

/**
 * 별도 벡터 저장소 검색의 플랫폼 DB 쪽 확인(ADR 0002 결정 5 "가시성 2단계", WD-720).
 *
 * <p>Qdrant 같은 별도 저장소는 플랫폼 DB 와 조인할 수 없다. 그래서 검색을 두 번 플랫폼 DB 에 묻는다.
 *
 * <ol>
 *   <li>검색 전: 이 조회자가 볼 수 있는 dataset_id 를 구해 저장소 필터로 넘긴다({@link #findVisibleDatasetIds}). 요청이 좁힌
 *       데이터셋·저장 유형도 여기서 미리 걸러 topK 후보를 엉뚱한 데이터셋이 차지하지 않게 한다.
 *   <li>검색 뒤: 저장소가 돌려준 후보 id 를 지금 SQL 조건 전부로 다시 확인하고, 결과에 붙일 표시 필드를 함께 읽는다({@link
 *       #findDatasetHits}, {@link #findChunkHits}). 저장소 반영이 늦어 지워졌거나 불허된 데이터셋, 적재가 끝나지 않은 문서의 청크가
 *       여기서 빠진다.
 * </ol>
 *
 * <p>pgvector 구현은 가시성을 검색 SQL 에 직접 렌더하므로 이 리포지토리를 쓰지 않는다. 구현은 jOOQ(PostgreSQL)·MyBatis(Tibero) 두
 * 벌이고 두 계정 RLS 계약 테스트가 같은 답을 고정한다. 테넌트는 RLS 가 좁힌다.
 */
public interface SearchCandidateRepository {

  /**
   * 이 가시성으로 볼 수 있는 데이터셋 id(오름차순).
   *
   * @param storageType 저장 유형으로 좁힌다. null 이면 좁히지 않는다
   * @param restrictTo 이 id 들로 좁힌다(요청의 datasetIds). null 이거나 비어 있으면 좁히지 않는다
   */
  List<Long> findVisibleDatasetIds(
      DatasetVisibility visibility, String storageType, Collection<Long> restrictTo);

  /**
   * 후보 데이터셋 id 중 지금도 보이고 조건에 맞는 것의 표시 필드. 점수는 0 이다(호출자가 저장소 점수를 붙인다). 순서는 보장하지 않는다.
   *
   * @param storageType 저장 유형 조건. null 이면 보지 않는다
   * @param space 의미 검색이면 그 공간 — 표시 열이 이 공간(차원·모델)을 가리키는 행만 남긴다(#392). 키워드 검색이면 null
   */
  List<DatasetSearchHit> findDatasetHits(
      Collection<Long> datasetIds,
      DatasetVisibility visibility,
      String storageType,
      EmbeddingSpace space);

  /**
   * 후보 청크 id 중 지금도 보이고 조건에 맞는 것의 표시 필드. 문서가 {@code COMPLETED} 인 청크만 남긴다. 점수는 0 이고 순서는 보장하지 않는다.
   *
   * @param restrictTo 요청이 좁힌 데이터셋. null 이거나 비어 있으면 보지 않는다
   * @param space 의미 검색이면 그 공간(표시 열 일치), 키워드 검색이면 null
   */
  List<DocumentSearchHit> findChunkHits(
      Collection<Long> chunkIds,
      DatasetVisibility visibility,
      Collection<Long> restrictTo,
      EmbeddingSpace space);
}
