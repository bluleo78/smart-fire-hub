package com.smartfirehub.dataset.rowsearch;

import com.smartfirehub.dataset.rowsearch.SearchSourceReader.SourceRow;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.util.TemporalSafeFetch;
import com.smartfirehub.pipeline.service.IncrementalCursorService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL 데이터 저장소의 행 검색 원본 읽기({@link SearchSourceStore}). WD-723 에서 {@link SearchSourceReader} 의
 * 본문을 그대로 옮겼다. 컬럼 이름은 호출자가 dataset_column 에서 얻은 값만 넘긴다(식별자는 인용).
 */
@Repository
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PgSearchSourceStore implements SearchSourceStore {

  @DataStoreDb private final DSLContext dsl;
  private final IncrementalCursorService cursorService;

  @Override
  public DataStoreBackend backend() {
    return DataStoreBackend.POSTGRESQL;
  }

  /**
   * PG 책갈피 후보 — 파이프라인 증분과 같은 {@link IncrementalCursorService} 를 쓴다(불변식이 같다). 예전처럼 트랜잭션 밖에서 읽는다 —
   * 클래스의 읽기 트랜잭션 안에서 부르면 now() 가 그 트랜잭션 시작 시각으로 고정된다.
   */
  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public OffsetDateTime captureCursorCandidate() {
    return cursorService.captureCandidate();
  }

  /** _updated_at >= cursor(없으면 전체) 이고 id > afterId 인 행을 id 순으로 limit 개. */
  @Override
  public List<SourceRow> fetchChanged(
      String table, List<String> columns, OffsetDateTime cursor, long afterId, int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT id, ")
            .append(quotedColumns(columns))
            .append(" FROM ")
            .append(DataSchema.qualify(table))
            .append(" WHERE id > ?");
    List<Object> params = new ArrayList<>(List.of(afterId));
    if (cursor != null) {
      // plain SQL 바인딩은 OffsetDateTime 을 varchar 로 보내므로 명시 캐스팅한다.
      sql.append(" AND _updated_at >= ?::timestamptz");
      params.add(cursor);
    }
    sql.append(" ORDER BY id LIMIT ?");
    params.add(limit);
    return dsl.fetch(sql.toString(), params.toArray())
        .map(r -> new SourceRow(r.get("id", Long.class), toValues(r, columns)));
  }

  /** 원본 테이블의 현재 OID — swap(DROP+RENAME)이면 바뀐다. */
  @Override
  public long currentOid(String table) {
    return dsl.fetchOne("SELECT ?::regclass::oid::bigint", DataSchema.qualify(table))
        .get(0, Long.class);
  }

  /**
   * 검색 결과 원본 조회: id → {컬럼: 값}. 없는 id 는 결과에 없다.
   *
   * <p>반환 컬럼에는 DATE/TIMESTAMP 도 들어올 수 있다(검색 대상 필드와 달리 제한 없음). {@code dsl.fetch} 로 읽으면 Java 날짜 객체가
   * 표현 못 하는 값(±infinity·BC·10000년·1582/DST 공백)이 오류 없이 다른 날짜가 되므로, 데이터 탭과 같은 {@link
   * TemporalSafeFetch} 로 읽어 이상 값은 PG 원문 텍스트, 정상 값은 예전과 같은 타입으로 둔다(#775, #769 와 같은 원인).
   */
  @Override
  public Map<Long, Map<String, Object>> fetchRows(
      String table, List<String> columns, Collection<Long> ids) {
    Map<Long, Map<String, Object>> result = new HashMap<>();
    if (ids.isEmpty()) return result;
    String cols = quotedColumns(columns);
    TemporalSafeFetch.fetch(
            dsl,
            "SELECT id"
                + (cols.isEmpty() ? "" : ", " + cols)
                + " FROM "
                + DataSchema.qualify(table)
                + " WHERE id = ANY(?)",
            (Object) ids.toArray(Long[]::new))
        .forEach(r -> result.put(r.get("id", Long.class), toValues(r, columns)));
    return result;
  }

  /**
   * 필터를 만족하는 행 id 를 id 순으로 많아야 {@code limit} 개(행 검색 필터 1단계, ADR 0002 결정 9·WD-722). Qdrant 색인은 원본 열을
   * 모르므로 데이터 저장소에서 먼저 필터를 평가해 id 로 넘긴다. 호출자는 상한+1 개를 요청해 상한을 넘는지 판단한다.
   */
  @Override
  public List<Long> matchingIds(String table, RowQueryFilter rowFilter, int limit) {
    CompiledFilter filter = rowFilter.toPostgres();
    List<Object> params = new ArrayList<>(filter.params());
    params.add(limit);
    return dsl.fetch(
            "SELECT t.id FROM "
                + DataSchema.qualify(table)
                + " t WHERE "
                + filter.sql()
                + " ORDER BY t.id LIMIT ?",
            params.toArray())
        .map(r -> r.get(0, Long.class));
  }

  /** 후보 id 중 필터를 만족하는 것(행 검색 필터 2단계 — 필터를 만족하는 행이 상한을 넘을 때의 사후 확인, WD-722). */
  @Override
  public Set<Long> filterIds(String table, Collection<Long> ids, RowQueryFilter rowFilter) {
    if (ids.isEmpty()) return Set.of();
    CompiledFilter filter = rowFilter.toPostgres();
    List<Object> params = new ArrayList<>();
    params.add(ids.toArray(Long[]::new));
    params.addAll(filter.params());
    return new HashSet<>(
        dsl.fetch(
                "SELECT t.id FROM "
                    + DataSchema.qualify(table)
                    + " t WHERE t.id = ANY(?) AND "
                    + filter.sql(),
                params.toArray())
            .map(r -> r.get(0, Long.class)));
  }

  /** 후보 id 중 원본에 아직 있는 것(행 색인 원본 대조 — 지워진 행의 점 정리, WD-722). */
  @Override
  public Set<Long> existingIds(String table, Collection<Long> ids) {
    if (ids.isEmpty()) return Set.of();
    return new HashSet<>(
        dsl.fetch(
                "SELECT id FROM " + DataSchema.qualify(table) + " WHERE id = ANY(?)",
                (Object) ids.toArray(Long[]::new))
            .map(r -> r.get(0, Long.class)));
  }

  /** 컬럼 이름을 인용해 쉼표로 잇는다(SELECT 목록용). 이름은 dataset_column 에서 온 값만 들어온다. */
  private static String quotedColumns(List<String> columns) {
    return columns.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(", "));
  }

  /** 레코드에서 요청 컬럼 값만 요청 순서대로 꺼낸다. */
  private static Map<String, Object> toValues(Record r, List<String> columns) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (String c : columns) values.put(c, r.get(c));
    return values;
  }
}
