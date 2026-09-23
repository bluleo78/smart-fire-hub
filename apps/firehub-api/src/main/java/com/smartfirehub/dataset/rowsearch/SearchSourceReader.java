package com.smartfirehub.dataset.rowsearch;

import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.global.tenant.DataSchema;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원본 데이터 테이블 읽기. 컬럼 이름은 호출자가 dataset_column 에서 얻은 값만 넘긴다(식별자는 인용).
 */
@Repository
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SearchSourceReader {

  private final DSLContext dsl;
  private final DataTableRowService dataTableRowService;

  /** 원본 행 한 건 — id 와 요청한 검색 대상 컬럼 값. */
  public record SourceRow(long id, Map<String, Object> values) {}

  /** _updated_at >= cursor(없으면 전체) 이고 id > afterId 인 행을 id 순으로 limit 개. */
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
  public long currentOid(String table) {
    return dsl.fetchOne("SELECT ?::regclass::oid::bigint", DataSchema.qualify(table)).get(0, Long.class);
  }

  /** 원본 테이블 전체 행 수 — 진행률 분모(total_rows)에 쓴다. 데이터 테이블 행 수 조회의 정본을 그대로 쓴다(테이블명 형식 검증 포함 — 생성 경로가 이미 같은 검증을 거쳐 실질 영향 없음). */
  public long countRows(String table) {
    return dataTableRowService.countRows(table);
  }

  /** 검색 결과 원본 조회: id → {컬럼: 값}. 없는 id 는 결과에 없다. */
  public Map<Long, Map<String, Object>> fetchRows(
      String table, List<String> columns, Collection<Long> ids) {
    Map<Long, Map<String, Object>> result = new HashMap<>();
    if (ids.isEmpty()) return result;
    String cols = quotedColumns(columns);
    dsl.fetch(
            "SELECT id" + (cols.isEmpty() ? "" : ", " + cols) + " FROM " + DataSchema.qualify(table)
                + " WHERE id = ANY(?)",
            (Object) ids.toArray(Long[]::new))
        .forEach(r -> result.put(r.get("id", Long.class), toValues(r, columns)));
    return result;
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
