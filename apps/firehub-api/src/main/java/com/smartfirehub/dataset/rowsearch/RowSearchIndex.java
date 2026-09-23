package com.smartfirehub.dataset.rowsearch;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 행 검색 색인 엔진 경계. 구현체는 row_id 와 점수만 반환하고 원본 행 조회는 호출자가 한다 — 조인에 의존하지 않아
 * 외부 벡터 DB 구현체로 교체해도 호출처가 바뀌지 않는다.
 */
public interface RowSearchIndex {

  // --- DDL / 생명주기 ---
  /** 색인을 비우고 새 차원으로 다시 만든다(이전 색인·_prev 모두 삭제). 설정·모델·차원 변경 시. */
  void recreate(IndexRef ref, int dim);

  /** 원본 테이블 교체(swap) 시: 현재 색인을 _prev 로 보관하고 빈 색인을 새로 만든다. */
  void rebuildReusing(IndexRef ref, int dim);

  /** 재구축 중(_prev 존재) 여부. */
  boolean hasPrev(IndexRef ref);

  /** _prev 삭제(재구축 완주 시). */
  void dropPrev(IndexRef ref);

  /** 색인·_prev 모두 삭제(검색 끄기·데이터셋 삭제). */
  void drop(IndexRef ref);

  // --- 쓰기 ---
  /** row_id 별 현재 source_hash. 없는 행은 맵에 없다. */
  Map<Long, String> existingHashes(IndexRef ref, Collection<Long> rowIds);

  /** _prev 에서 같은 source_hash·같은 모델의 벡터를 찾아 복사한다. 복사했으면 true. */
  boolean upsertReusing(IndexRef ref, long rowId, String sourceText, String sourceHash, String model);

  /** 임베딩까지 끝난 행들을 넣는다. 같은 (row_id, chunk_no) 가 있으면 텍스트·해시·벡터·모델을 덮어쓴다. */
  void upsert(IndexRef ref, List<IndexedRow> rows);

  /** 지정한 row_id 의 색인 행을 지운다(검색 대상 필드가 모두 빈 행 등). 빈 목록이면 아무것도 하지 않는다. */
  void deleteRows(IndexRef ref, Collection<Long> rowIds);

  /** 원본에 없는 row_id 를 지운다. 지운 행 수. */
  int deleteMissing(IndexRef ref);

  /** 색인 행 수. 색인 테이블이 아직 없으면 0. */
  long count(IndexRef ref);

  // --- 검색 ---
  /** 코사인 유사도 상위. filter 는 {@link RowFilterCompiler} 로 검증된 조건만 온다. */
  List<RowHit> semantic(IndexRef ref, float[] queryVec, CompiledFilter filter, int limit);

  /** pg_trgm word_similarity 상위. */
  List<RowHit> keyword(IndexRef ref, String query, CompiledFilter filter, int limit);
}
