package com.smartfirehub.dataset.rowsearch;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.rowsearch.dto.SearchIndexStatusResponse;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.global.tenant.TenantContext;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 검색 탭 설정: 대상 필드 저장(켜기/끄기)·상태 조회·수동 재색인. 실제 색인은 스윕이 한다. */
@Service
@RequiredArgsConstructor
public class SearchIndexSettingsService {

  /** 색인 텍스트를 만들 수 있는 컬럼 타입. 컬럼 타입 변경 409 판정(DatasetService)도 이 집합 하나를 쓴다. */
  public static final Set<String> SEARCHABLE_TYPES = Set.of("TEXT", "VARCHAR");

  private final DatasetRepository datasetRepository;
  private final DatasetColumnRepository columnRepository;
  private final SearchColumnRepository searchColumns;
  private final SearchIndexStateRepository states;
  private final RowSearchIndex index;
  private final EmbeddingProviderFactory embeddingFactory;

  /**
   * 현재 검색 설정·색인 진행 상태. 색인 상태 행이 없으면 OFF.
   *
   * <p>상태 행이 있어도 검색 대상 필드가 하나도 없으면 OFF 로 본다 — 검색 대상 컬럼을 컬럼 삭제로 모두 없앤 직후(다음
   * 스윕이 정리하기 전)에 "사용 가능·필드 없음"이라는 모순된 상태를 보여주지 않기 위해서다.
   */
  @Transactional(readOnly = true)
  public SearchIndexStatusResponse getStatus(long datasetId) {
    requireTableDataset(datasetId);
    SearchIndexState state = states.find(datasetId).orElse(null);
    if (state == null) return SearchIndexStatusResponse.off();
    SearchConfig config = searchColumns.findConfig(datasetId);
    if (config.isEmpty()) return SearchIndexStatusResponse.off();
    return new SearchIndexStatusResponse(
        true,
        config.columnNames(),
        state.status(),
        state.indexedRows(),
        state.totalRows(),
        state.lastSyncedAt(),
        state.lastError(),
        state.embeddingModel());
  }

  /**
   * 검색 대상 필드를 통째로 교체한다. 빈 목록이면 검색을 끄고 색인 테이블·상태를 지운다.
   *
   * <p>필드가 실제로 바뀐 경우에만 SYNCING 으로 표시한다 — 같은 필드 재저장은 상태를 건드리지 않는다.
   */
  @Transactional
  public SearchIndexStatusResponse update(long datasetId, List<String> fields) {
    DatasetResponse dataset = requireTableDataset(datasetId);
    Map<String, DatasetColumnResponse> byName =
        columnRepository.findByDatasetId(datasetId).stream()
            .collect(Collectors.toMap(DatasetColumnResponse::columnName, Function.identity()));
    for (String f : fields) {
      DatasetColumnResponse col = byName.get(f);
      if (col == null) throw new IllegalArgumentException("알 수 없는 필드입니다: " + f);
      if (!SEARCHABLE_TYPES.contains(col.dataType())) {
        throw new IllegalArgumentException("검색 대상은 TEXT/VARCHAR 필드만 가능합니다: " + f);
      }
    }
    boolean changed =
        !new HashSet<>(searchColumns.findConfig(datasetId).columnNames())
            .equals(new HashSet<>(fields));
    searchColumns.setSearchable(datasetId, fields);
    if (fields.isEmpty()) {
      // 검색 끄기: 상태 행과 색인 테이블을 함께 지운다(색인 테이블은 FK 가 없어 자동 정리되지 않음).
      states.delete(datasetId);
      index.drop(new IndexRef(TenantContext.require(), datasetId, dataset.tableName()));
      return SearchIndexStatusResponse.off();
    }
    // 색인 테이블의 vector 컬럼 차원 상한을 넘는 모델이면 켜는 시점에 거절한다(스윕에서 조용히 실패하지 않도록).
    int dim = embeddingFactory.current().dimension();
    if (dim > PgRowSearchIndex.MAX_DIM) {
      throw new IllegalArgumentException(
          "현재 임베딩 모델 차원("
              + dim
              + ")은 검색 색인이 지원하는 최대 "
              + PgRowSearchIndex.MAX_DIM
              + " 을 넘습니다");
    }
    states.createIfAbsent(datasetId); // 설정이 바뀌면 스윕이 config_hash 차이로 전체 재색인한다
    // 확인 창이 "다시 색인"을 약속했으니 스윕 전까지도 "사용 가능"이 아닌 "색인 중"으로 보여준다.
    // 이전 실패의 백오프도 풀어 다음 스윕이 바로 집어 가게 한다.
    if (changed) states.markSyncing(datasetId);
    return getStatus(datasetId);
  }

  /** 수동 재색인: 다음 스윕이 전체 재색인하도록 표시한다. 검색 미설정이면 400. */
  @Transactional
  public void reindex(long datasetId) {
    requireTableDataset(datasetId);
    if (states.find(datasetId).isEmpty()) {
      throw new SearchIndexNotConfiguredException(datasetId);
    }
    states.forceFull(datasetId);
  }

  /** 데이터셋 존재(404)와 TABLE 저장 방식(400)을 확인한다. */
  private DatasetResponse requireTableDataset(long datasetId) {
    return requireTableDataset(datasetRepository, datasetId);
  }

  /**
   * 데이터셋 존재(404)와 TABLE 저장 방식(400)을 확인한다. 행 검색 서비스({@link RowSearchService})도 같은 판정·메시지를
   * 쓰도록 패키지 공용으로 둔다.
   */
  static DatasetResponse requireTableDataset(DatasetRepository datasetRepository, long datasetId) {
    DatasetResponse d =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
    if (!"TABLE".equals(d.storageType())) {
      throw new IllegalArgumentException("행 검색은 TABLE 데이터셋만 지원합니다");
    }
    return d;
  }
}
