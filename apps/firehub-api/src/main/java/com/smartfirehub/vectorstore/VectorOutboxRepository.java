package com.smartfirehub.vectorstore;

import java.util.List;

/**
 * 벡터 아웃박스와 순번(WD-721, ADR 0002 결정 6). 별도 벡터 저장소(Qdrant)의 삭제를 플랫폼 DB 트랜잭션에 묶고, 쓰기·삭제의 순서를 순번으로 정한다.
 * jOOQ(PG)·MyBatis(Tibero) 두 벌이고, 테이블은 테넌트 정책(RLS) 대상이라 현재 테넌트 행만 보인다.
 *
 * <p>순번은 플랫폼 DB 시퀀스 {@code vector_seq} 하나에서 받는다. 점을 쓸 때 받은 순번을 payload {@code seq} 에, 삭제를 쌓을 때 받은
 * 순번을 행에 싣는다. 워커는 행의 순번보다 작은 점만 지운다 — 시각을 비교하지 않는다(파드·DB 시계 차이, Tibero TSTZ 금지 WD-260).
 */
public interface VectorOutboxRepository {

  /** 쌓인 삭제 한 건. */
  record Entry(
      long seq,
      long tenantId,
      VectorOutboxKind kind,
      Long targetId,
      Integer keepDim,
      int attempts) {}

  /** 새 순번을 받는다(점 쓰기용). 트랜잭션과 무관하게 늘 증가한다. */
  long nextSeq();

  /**
   * 현재 테넌트에 삭제를 쌓는다. 호출한 트랜잭션에 합류한다 — 부모 행 삭제·표시 열 비우기가 롤백되면 이 행도 사라진다. 순번은 이때 받는다.
   *
   * @param targetId 데이터셋 id 또는 문서 파일 id. null 이면 테넌트 전체
   * @param keepDim 건드리지 않을 차원. null 이면 모든 차원
   * @return 쌓은 행의 순번
   */
  long enqueue(VectorOutboxKind kind, Long targetId, Integer keepDim);

  /** 현재 테넌트에 쌓인 행을 순번 순서로 {@code limit} 건. */
  List<Entry> findPending(int limit);

  /** 반영을 마친 행을 지운다. */
  void delete(long seq);

  /** 반영 실패를 남긴다(시도 수 +1, 마지막 오류). 행은 남아 다음 폴링이 다시 한다. 오류 글은 500자에서 자른다. */
  void markFailed(long seq, String error);
}
