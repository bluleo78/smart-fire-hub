package com.smartfirehub.dataset.rowsearch;

import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 행 검색의 원본 데이터 테이블 읽기 파사드. 현재 테넌트의 데이터 저장소 백엔드로 {@link SearchSourceStore} 구현을 골라 위임한다(WD-723 — 그전에는
 * PostgreSQL 만 직접 읽었다).
 *
 * <p>왜 호출 때마다 고르는가: 한 JVM 에 PG 테넌트와 Tibero 테넌트가 함께 있어 빈 조건부 등록으로는 가를 수 없다. 백엔드 해석은 {@link
 * DataStoreResolver} 가 캐시하므로 호출마다 DB 를 읽지 않는다.
 */
@Component
public class SearchSourceReader {

  /** 원본 행 한 건 — id 와 요청한 검색 대상 컬럼 값. */
  public record SourceRow(long id, Map<String, Object> values) {}

  private final Map<DataStoreBackend, SearchSourceStore> stores =
      new EnumMap<>(DataStoreBackend.class);
  private final DataStoreResolver resolver;
  private final DataTableRowService dataTableRowService;

  public SearchSourceReader(
      List<SearchSourceStore> stores,
      DataStoreResolver resolver,
      DataTableRowService dataTableRowService) {
    for (SearchSourceStore s : stores) this.stores.put(s.backend(), s);
    this.resolver = resolver;
    this.dataTableRowService = dataTableRowService;
  }

  /** 현재 테넌트 백엔드의 구현. 없으면 설정 오류다(행 검색 판정이 막았어야 한다). */
  private SearchSourceStore store() {
    DataStoreBackend backend = resolver.currentBackend();
    SearchSourceStore store = stores.get(backend);
    if (store == null) {
      throw new IllegalStateException("행 검색 원본 읽기를 지원하지 않는 데이터 저장소: " + backend);
    }
    return store;
  }

  /** {@link SearchSourceStore#fetchChanged}. */
  public List<SourceRow> fetchChanged(
      String table, List<String> columns, OffsetDateTime cursor, long afterId, int limit) {
    return store().fetchChanged(table, columns, cursor, afterId, limit);
  }

  /** {@link SearchSourceStore#currentOid}. */
  public long currentOid(String table) {
    return store().currentOid(table);
  }

  /** {@link SearchSourceStore#replaceKeepsTable} — 현재 테넌트 저장소가 교체 적재 때 테이블을 그대로 두는지(WD-745). */
  public boolean replaceKeepsTable() {
    return store().replaceKeepsTable();
  }

  /** 원본 테이블 전체 행 수 — 진행률 분모(total_rows). 데이터 테이블 행 수의 정본(이미 백엔드별로 위임하는 파사드)을 그대로 쓴다. */
  public long countRows(String table) {
    return dataTableRowService.countRows(table);
  }

  /** {@link SearchSourceStore#fetchRows}. */
  public Map<Long, Map<String, Object>> fetchRows(
      String table, List<String> columns, Collection<Long> ids) {
    return store().fetchRows(table, columns, ids);
  }

  /** {@link SearchSourceStore#matchingIds}. */
  public List<Long> matchingIds(String table, RowQueryFilter filter, int limit) {
    return store().matchingIds(table, filter, limit);
  }

  /** {@link SearchSourceStore#filterIds}. */
  public Set<Long> filterIds(String table, Collection<Long> ids, RowQueryFilter filter) {
    return store().filterIds(table, ids, filter);
  }

  /** {@link SearchSourceStore#existingIds}. */
  public Set<Long> existingIds(String table, Collection<Long> ids) {
    return store().existingIds(table, ids);
  }

  /** {@link SearchSourceStore#captureCursorCandidate} — 데이터 저장소 시계로 책갈피 후보를 잡는다. */
  public OffsetDateTime captureCursorCandidate() {
    return store().captureCursorCandidate();
  }
}
