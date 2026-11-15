package com.smartfirehub.dataset.rowsearch;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 행 검색 색인 엔진 경계. 구현체는 row_id 와 점수만 반환하고 원본 행 조회는 호출자가 한다 — 조인에 의존하지 않아 외부 벡터 DB 구현체로 교체해도 호출처가 바뀌지
 * 않는다.
 */
public interface RowSearchIndex {

  // --- DDL / 생명주기 ---
  /** 색인을 비우고 새 차원으로 다시 만든다(모든 세대 삭제). 설정·모델·차원 변경 시. */
  void recreate(IndexRef ref, int dim);

  /**
   * 원본 테이블 교체(swap) 뒤 새 세대를 시작한다. 이전 세대는 같은 원문(source_hash)의 벡터를 다시 쓰는 재사용({@link #upsertReusing})
   * 용으로만 남는다. 세대 번호는 호출자가 상태 행에서 먼저 올리고({@code markSourceOid}) 그 값을 실은 참조로 부른다.
   *
   * <p>PG 구현은 지금 색인 테이블을 {@code _prev} 로 바꾸고 빈 테이블을 새로 만든다. Qdrant 구현은 컬렉션만 준비한다(세대는 점 payload).
   */
  void startGeneration(IndexRef ref, int dim);

  /**
   * 옛 판으로 만든 지금 세대 색인을 지금 판 키워드 색인으로 올린다(WD-725 — PG 는 GIN → GiST). 동기화가 쓰기 전에 부르고, 구현은 한 번 올린 색인을
   * 기억해 다시 묻지 않는다. 할 일이 없는 구현(Qdrant)은 기본값 그대로 아무것도 하지 않는다.
   */
  default void ensureKeywordIndex(IndexRef ref) {}

  /** 이전 세대가 남아 있는지(재구축 중 — 재사용을 시도할지). */
  boolean hasPreviousGeneration(IndexRef ref);

  /** 이전 세대를 모두 지운다(재구축 완주 시). */
  void dropPreviousGenerations(IndexRef ref);

  /** 색인을 모든 세대째 지운다(검색 끄기·데이터셋 삭제). 세대 무관 참조로 불러도 된다. */
  void drop(IndexRef ref);

  // --- 쓰기 ---
  /** row_id 별 현재 source_hash. 없는 행은 맵에 없다. */
  Map<Long, String> existingHashes(IndexRef ref, Collection<Long> rowIds);

  /**
   * 이전 세대에서 같은 source_hash·같은 모델의 벡터를 찾아 지금 세대로 복사한다. 복사한 row_id 집합을 돌려준다 — 나머지는 호출자가 새로 임베딩한다.
   *
   * <p>배치 단위인 이유(WD-743): 행마다 부르면 swap 재구축이 행 수만큼 왕복한다(Qdrant 는 조회 1번·쓰기 2번). 구현은 배치 크기와 무관한 횟수로
   * 처리한다 — PG 는 한 문장, Qdrant 는 해시 묶음 조회 + dense·키워드 쓰기 한 번씩.
   */
  Set<Long> upsertReusing(IndexRef ref, List<ReuseCandidate> rows, String model);

  /**
   * {@link #upsertReusing} 과 같되 <b>지금 세대</b>의 다른 행에서 같은 원문 해시·같은 모델의 벡터를 찾는다(WD-745). 교체 적재가 테이블을
   * 그대로 두는 저장소(Tibero)는 REPLACE 뒤에도 세대가 바뀌지 않아, 새 ID 로 다시 들어온 같은 원문을 이 길로 재사용한다.
   *
   * <p>기본값은 아무것도 재사용하지 않는다 — PG 색인은 PG 데이터 저장소에서만 쓰이고, PG 의 교체 적재는 swap(세대 재구축)이라 이 길을 타지 않는다.
   */
  default Set<Long> upsertReusingSameGeneration(
      IndexRef ref, List<ReuseCandidate> rows, String model) {
    return Set.of();
  }

  /** 임베딩까지 끝난 행들을 넣는다. 같은 (row_id, chunk_no) 가 있으면 텍스트·해시·벡터·모델을 덮어쓴다. */
  void upsert(IndexRef ref, List<IndexedRow> rows);

  /**
   * 색인 행의 벡터만 비운다(키워드는 남겨 키워드 검색 유지). 모든 세대가 대상이라 세대 무관 참조로 불러도 된다. 등급이 임베딩 공급자를 허용하지 않게 된 데이터셋의 외부
   * 유래 벡터 정리용(S3 §4.3). 색인 테이블이 아직 없으면 아무것도 하지 않는다.
   */
  void clearEmbeddings(IndexRef ref);

  /** 지정한 row_id 의 색인 행을 지운다(검색 대상 필드가 모두 빈 행 등). 빈 목록이면 아무것도 하지 않는다. */
  void deleteRows(IndexRef ref, Collection<Long> rowIds);

  /** 원본에 없는 row_id 를 지운다. 지운 행 수. */
  int deleteMissing(IndexRef ref);

  /**
   * {@link #deleteMissing} 을 주기 제한 없이 지금 한다(WD-745). 교체 적재 뒤 옛 ID 의 행이 한꺼번에 남았을 때 부른다. 주기 제한이 없는
   * 구현(PG)은 기본값 그대로 {@link #deleteMissing} 과 같다.
   */
  default int deleteMissingNow(IndexRef ref) {
    return deleteMissing(ref);
  }

  /** 색인 행 수. 색인 테이블이 아직 없으면 0. */
  long count(IndexRef ref);

  // --- 검색 ---
  /**
   * 코사인 유사도 상위. filter 는 {@link RowQueryFilter#validated} 로 검증된 조건만 온다 — 평가 방법은 구현이 정한다(PG 는 원본과
   * 조인, Qdrant 는 데이터 저장소에서 2단계).
   */
  List<RowHit> semantic(IndexRef ref, float[] queryVec, RowQueryFilter filter, int limit);

  /** 키워드 상위(PG 는 pg_trgm word_similarity, Qdrant 는 음절 바이그램 sparse). */
  List<RowHit> keyword(IndexRef ref, String query, RowQueryFilter filter, int limit);
}
