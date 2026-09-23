package com.smartfirehub.dataset.rowsearch;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.rowsearch.dto.RowSearchRequest;
import com.smartfirehub.dataset.rowsearch.dto.RowSearchResponse;
import com.smartfirehub.embedding.EmbeddingException;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.util.RankFusion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 행 검색(엔진 중립): 모드 분기 → RRF → 원본 조회. 원본 테이블이 교체(swap)돼 색인이 옛 id 를 가리키면 결과를 내지
 * 않는다(STALE) — 예전 벡터가 새 행으로 잘못 매칭되는 것을 원천 차단(설계 3.4).
 */
@Service
@RequiredArgsConstructor
public class RowSearchService {

  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;
  private static final int CANDIDATE_POOL = 50;
  private static final List<String> SOURCE_NAMES = List.of("SEMANTIC", "KEYWORD");

  private final DatasetRepository datasetRepository;
  private final DatasetColumnRepository columnRepository;
  private final SearchIndexStateRepository states;
  private final SearchColumnRepository searchColumns;
  private final SearchSourceReader reader;
  private final RowSearchIndex index;
  private final EmbeddingProviderFactory embeddingFactory;

  /**
   * 메서드 수준 트랜잭션을 걸지 않는다 — 여기서 부르는 DB 접근(데이터셋·컬럼·상태·검색 필드 저장소, 원본 리더, 색인,
   * 임베딩 설정 조회)은 모두 자체 {@code @Transactional} 경계를 가진 빈이라 호출마다 짧은 트랜잭션에서 RLS 테넌트 GUC 가 선다
   * (TenantAwareTransactionManager). 그래서 질의 임베딩(외부 HTTP 호출) 동안 DB 커넥션·트랜잭션을 붙잡지 않는다.
   */
  public RowSearchResponse search(long datasetId, RowSearchRequest req) {
    if (req.query() == null || req.query().isBlank()) {
      throw new IllegalArgumentException("검색어가 비어 있습니다");
    }
    String mode = req.mode() == null ? "HYBRID" : req.mode().toUpperCase(Locale.ROOT);
    if (!Set.of("HYBRID", "SEMANTIC", "KEYWORD").contains(mode)) {
      throw new IllegalArgumentException("mode 는 HYBRID, SEMANTIC, KEYWORD 중 하나여야 합니다");
    }
    int limit = req.limit() == null ? DEFAULT_LIMIT : Math.max(1, Math.min(req.limit(), MAX_LIMIT));

    DatasetResponse dataset = SearchIndexSettingsService.requireTableDataset(datasetRepository, datasetId);
    SearchIndexState state =
        states.find(datasetId).orElseThrow(() -> new SearchIndexNotConfiguredException(datasetId));
    // 상태 행이 있어도 검색 대상 필드가 없으면 미설정으로 본다 — 검색 대상 컬럼을 삭제로 모두 없앤 직후(다음 스윕이
    // 상태 행·색인 테이블을 정리하기 전)에 낡은 색인으로 결과를 내지 않기 위해서다(getStatus 의 OFF 판정과 같은 기준).
    if (searchColumns.findConfig(datasetId).isEmpty()) {
      throw new SearchIndexNotConfiguredException(datasetId);
    }

    Map<String, String> columnTypes = new LinkedHashMap<>();
    for (DatasetColumnResponse c : columnRepository.findByDatasetId(datasetId)) {
      columnTypes.put(c.columnName(), c.dataType());
    }
    CompiledFilter filter = RowFilterCompiler.compile(new RowFilter(req.filters()), columnTypes);
    List<String> returnColumns = resolveReturnColumns(req.columns(), columnTypes);

    IndexRef ref = new IndexRef(TenantContext.require(), datasetId, dataset.tableName());
    long oid = reader.currentOid(ref.sourceTable());
    if (state.sourceTableOid() == null) {
      // 첫 스윕 전(상태 행만 있고 원본 OID·색인 테이블이 아직 없음) — 없는 색인 테이블을 조회하지 않고 현재 상태
      // (SYNCING/ERROR)를 그대로 알린다. 낡은 것이 아니라 아직 만드는 중이므로 STALE 이 아니다.
      return empty(state, state.status());
    }
    if (state.sourceTableOid() != oid) {
      return empty(state, "STALE");
    }

    // 색인 벡터와 질의 벡터가 같은 모델·차원이어야 의미 검색이 성립한다. 모델이 바뀐 뒤 다음 스윕이 재색인하기 전에는
    // 같은 차원이면 조용히 틀린 결과, 다른 차원이면 pgvector 오류(500)가 나므로 의미 검색을 건너뛴다.
    // provider 는 의미 검색이 필요한 모드에서만 한 번 얻는다.
    EmbeddingProvider provider = providerFor(mode);
    boolean vectorsUsable =
        provider != null
            && Objects.equals(provider.modelId(), state.embeddingModel())
            && Objects.equals(provider.dimension(), state.embeddingDim());

    boolean degraded = false;
    List<RankFusion.Fused<RowHit>> fused;
    switch (mode) {
      case "SEMANTIC" -> {
        if (!vectorsUsable) {
          // 재색인이 끝나야 의미 검색이 가능하다 — "색인 중"으로 알리고 결과는 내지 않는다.
          return empty(state, "SYNCING");
        }
        fused = single(index.semantic(ref, embed(provider, req.query()), filter, limit), 0);
      }
      case "KEYWORD" -> fused = single(index.keyword(ref, req.query(), filter, limit), 1);
      default -> {
        // 질의 임베딩을 색인 질의보다 먼저 구한다 — 임베딩 실패 여부로 degraded 를 정한 뒤 색인만 차례로 조회한다.
        float[] queryVec = null;
        if (!vectorsUsable) {
          // 모델·차원이 바뀌어 재색인 대기 중 — 키워드만으로 답하고 degraded 로 알린다.
          degraded = true;
        } else {
          try {
            queryVec = embed(provider, req.query());
          } catch (EmbeddingException e) {
            // 임베딩 서버 장애 시 키워드만으로 답한다 — 에이전트가 degraded 를 보고 알린다.
            degraded = true;
          }
        }
        // 후보 풀이 limit 보다 작으면 limit(최대 100)을 채울 수 없으므로 limit 만큼은 넓힌다.
        int pool = Math.max(CANDIDATE_POOL, limit);
        List<RowHit> keyword = index.keyword(ref, req.query(), filter, pool);
        List<RowHit> semantic = queryVec == null ? List.of() : index.semantic(ref, queryVec, filter, pool);
        fused = RankFusion.fuse(List.of(semantic, keyword), RowHit::rowId, limit);
      }
    }

    Map<Long, Map<String, Object>> rows =
        reader.fetchRows(ref.sourceTable(), returnColumns, fused.stream().map(f -> f.hit().rowId()).toList());
    List<RowSearchResponse.Hit> hits = new ArrayList<>();
    for (RankFusion.Fused<RowHit> f : fused) {
      Map<String, Object> row = rows.get(f.hit().rowId());
      if (row == null) continue; // 색인 반영 전 삭제된 행
      List<String> matchedBy = f.sources().stream().map(SOURCE_NAMES::get).toList();
      hits.add(new RowSearchResponse.Hit(f.hit().rowId(), f.score(), matchedBy, row));
    }
    return new RowSearchResponse(indexStatus(state, state.status()), degraded, hits);
  }

  /** 결과 없이 색인 상태만 알리는 응답(첫 스윕 전·STALE·재색인 대기). */
  private static RowSearchResponse empty(SearchIndexState state, String status) {
    return new RowSearchResponse(indexStatus(state, status), false, List.of());
  }

  /** 응답에 싣는 색인 상태 — 상태값은 호출부가 정하고(STALE 등 덮어쓰기), 진행 수치는 상태 행에서 가져온다. */
  private static RowSearchResponse.IndexStatus indexStatus(SearchIndexState state, String status) {
    return new RowSearchResponse.IndexStatus(status, state.indexedRows(), state.totalRows());
  }

  /** 단일 모드 결과를 원래 점수 그대로 Fused 로 감싼다(source: 0=SEMANTIC, 1=KEYWORD). */
  private static List<RankFusion.Fused<RowHit>> single(List<RowHit> hits, int source) {
    return hits.stream().map(h -> new RankFusion.Fused<>(h, h.score(), Set.of(source))).toList();
  }

  /**
   * 모드에 필요한 임베딩 provider. KEYWORD 는 필요 없어 null. provider 구성 자체가 실패하면(미지원·키 누락) SEMANTIC 은
   * 예외를 그대로 올리고, HYBRID 는 null 을 돌려 키워드만으로 답하게 한다(임베딩 서버 장애와 같은 degraded 처리).
   */
  private EmbeddingProvider providerFor(String mode) {
    if ("KEYWORD".equals(mode)) return null;
    try {
      return embeddingFactory.current();
    } catch (EmbeddingException e) {
      if ("SEMANTIC".equals(mode)) throw e;
      return null;
    }
  }

  /** 질의 임베딩 — 실패 시 EmbeddingException 이 그대로 올라간다(HYBRID 만 잡아서 degraded 로 폴백). */
  private static float[] embed(EmbeddingProvider provider, String query) {
    return provider.embed(List.of(query)).get(0);
  }

  /** 반환 컬럼: 요청이 없으면 GEOMETRY 제외 전체, 있으면 존재·비GEOMETRY 검증. */
  private static List<String> resolveReturnColumns(List<String> requested, Map<String, String> columnTypes) {
    if (requested == null || requested.isEmpty()) {
      return columnTypes.entrySet().stream()
          .filter(e -> !"GEOMETRY".equals(e.getValue()))
          .map(Map.Entry::getKey)
          .toList();
    }
    for (String c : requested) {
      String type = columnTypes.get(c);
      if (type == null) throw new IllegalArgumentException("columns: 알 수 없는 컬럼 '" + c + "'");
      if ("GEOMETRY".equals(type)) throw new IllegalArgumentException("columns: GEOMETRY 컬럼은 반환할 수 없습니다");
    }
    return requested;
  }
}
