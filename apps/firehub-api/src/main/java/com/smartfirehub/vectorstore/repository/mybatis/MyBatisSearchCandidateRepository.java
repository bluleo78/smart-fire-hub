package com.smartfirehub.vectorstore.repository.mybatis;

import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import com.smartfirehub.vectorstore.SearchCandidateRepository;
import java.util.Collection;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SearchCandidateRepository} 의 MyBatis 구현(WD-720) — {@code JooqSearchCandidateRepository} 와
 * 같은 의미. Tibero+qdrant 조합이 쓴다. 가시성은 공용 조각 {@code DatasetVisibility.visible} 이 렌더한다(jOOQ 판의 {@code
 * visibleSql} 렌더 문자열은 Tibero 유효성을 보장하지 않는다). 두 계정 RLS 계약 테스트가 두 구현을 같은 답으로 고정한다.
 */
@PlatformMyBatisRepository
@Transactional(readOnly = true)
public class MyBatisSearchCandidateRepository implements SearchCandidateRepository {

  private final SearchCandidateMapper mapper;

  /** 매퍼를 주입받는다(플랫폼 MyBatis 빈). */
  public MyBatisSearchCandidateRepository(SearchCandidateMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<Long> findVisibleDatasetIds(
      DatasetVisibility visibility, String storageType, Collection<Long> restrictTo) {
    return mapper.selectVisibleDatasetIds(
        MyBatisDatasetVisibility.params(visibility), storageType, chunksOrNull(restrictTo));
  }

  @Override
  public List<DatasetSearchHit> findDatasetHits(
      Collection<Long> datasetIds,
      DatasetVisibility visibility,
      String storageType,
      EmbeddingSpace space) {
    if (datasetIds.isEmpty()) return List.of();
    return mapper.selectDatasetHits(
        InChunks.of(datasetIds),
        MyBatisDatasetVisibility.params(visibility),
        storageType,
        space == null ? null : space.dimension().size(),
        space == null ? null : space.model());
  }

  @Override
  public List<DocumentSearchHit> findChunkHits(
      Collection<Long> chunkIds,
      DatasetVisibility visibility,
      Collection<Long> restrictTo,
      EmbeddingSpace space) {
    if (chunkIds.isEmpty()) return List.of();
    return mapper.selectChunkHits(
        InChunks.of(chunkIds),
        MyBatisDatasetVisibility.params(visibility),
        chunksOrNull(restrictTo),
        space == null ? null : space.dimension().size(),
        space == null ? null : space.model());
  }

  /** 선택 목록 — 비었으면 null(조건 없음), 있으면 500 개씩 나눈다. */
  private static List<List<Long>> chunksOrNull(Collection<Long> ids) {
    return ids == null || ids.isEmpty() ? null : InChunks.of(ids);
  }
}
