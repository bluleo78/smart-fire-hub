package com.smartfirehub.dataset.rowsearch;

import com.smartfirehub.dataset.rowsearch.SearchSourceReader.SourceRow;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 행 검색이 데이터 저장소(원본 데이터 테이블)에서 읽는 일의 저장소별 구현 계약(WD-723).
 *
 * <p>왜 나누는가: 한 JVM 에 PostgreSQL 테넌트와 Tibero 테넌트가 함께 있다. 행 검색 색인(Qdrant)은 저장소와 무관하지만 원본 읽기·필터 평가·증분
 * 책갈피는 저장소 SQL·시계에 묶인다. 그래서 호출자는 {@link SearchSourceReader}(파사드)만 보고, 파사드가 테넌트 백엔드로 구현을 고른다 —
 * {@code DataRowStore} 와 같은 방식이다.
 *
 * <p>모든 메서드는 현재 테넌트({@code TenantContext}) 기준이다. 컬럼 이름은 호출자가 dataset_column 에서 얻은 메타데이터 이름(소문자)만
 * 넘긴다.
 */
public interface SearchSourceStore {

  /** 이 구현이 담당하는 백엔드. */
  DataStoreBackend backend();

  /** 원본 테이블 실체의 식별자 — 테이블을 지우고 같은 이름으로 다시 만들면(swap·재생성) 바뀐다. PG 는 OID, Tibero 는 OBJECT_ID. */
  long currentOid(String table);

  /**
   * 교체 적재(REPLACE)가 원본 테이블을 그대로 두고 행만 지우고 다시 넣는지(WD-745). 그러면 {@link #currentOid} 가 바뀌지 않아 세대
   * 재구축(이전 세대 벡터 재사용)이 일어나지 않고, 새 ID 의 행이 모두 변경분이 된다. 참이면 동기화가 새 행의 벡터를 지금 세대 안에서도 원문 해시로 찾아 다시 쓴다.
   *
   * <p>PG 는 임시 테이블 swap 이라 OID 가 바뀌므로 거짓(기본값), Tibero 는 같은 테이블에 {@code DELETE FROM} 뒤 다시 넣어 참이다.
   */
  default boolean replaceKeepsTable() {
    return false;
  }

  /**
   * _updated_at >= cursor(없으면 전체) 이고 id > afterId 인 행을 id 순으로 limit 개. cursor 는 이 저장소 시계로 잡은 값이다.
   */
  List<SourceRow> fetchChanged(
      String table, List<String> columns, OffsetDateTime cursor, long afterId, int limit);

  /** 검색 결과 원본 조회: id → {컬럼: 값}. 없는 id 는 결과에 없다. */
  Map<Long, Map<String, Object>> fetchRows(
      String table, List<String> columns, Collection<Long> ids);

  /** 필터를 만족하는 행 id 를 id 순으로 많아야 limit 개(행 검색 필터 1단계, WD-722). filter 는 비어 있지 않아야 한다. */
  List<Long> matchingIds(String table, RowQueryFilter filter, int limit);

  /** 후보 id 중 필터를 만족하는 것(행 검색 필터 2단계, WD-722). filter 는 비어 있지 않아야 한다. */
  Set<Long> filterIds(String table, Collection<Long> ids, RowQueryFilter filter);

  /** 후보 id 중 원본에 아직 있는 것(지워진 행의 점 정리, WD-722). */
  Set<Long> existingIds(String table, Collection<Long> ids);

  /**
   * 증분 책갈피 후보(UTC) — 처리 <b>직전</b>에, 커밋 안 된 가장 오래된 트랜잭션 시작 시각 이하로 잡는다. {@code _updated_at} 을 채우는 그
   * 저장소의 시계에서 읽어야 한다(PG 와 Tibero 시계를 섞지 않는다).
   */
  OffsetDateTime captureCursorCandidate();
}
