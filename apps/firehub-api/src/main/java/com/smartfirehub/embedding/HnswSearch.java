package com.smartfirehub.embedding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.RecordMapper;
import org.jooq.impl.DSL;

/**
 * 필터가 걸린 HNSW 검색이 k 건을 채우도록 iterative scan 을 켠다(pgvector 0.8+, PgRowSearchIndex:203 선례).
 *
 * <p>전역 HNSW 는 테넌트·데이터셋·모델 필터 전에 후보를 뽑으므로, 필터가 대부분을 거르면 k 미달이 된다. {@code relaxed_order} 는 순서가 약간
 * 어긋날 수 있어 호출자가 바깥에서 점수로 재정렬한다. 같은 트랜잭션의 {@code SET LOCAL} 이라 트랜잭션이 끝나면 자동 복원된다.
 */
public final class HnswSearch {

  /** HNSW 후보 큐 크기. pgvector 기본 40 보다 넉넉히 잡아 필터 뒤에도 k 건이 남게 한다. */
  public static final int EF_SEARCH = 200;

  private HnswSearch() {}

  /** 현재 트랜잭션에 iterative scan(relaxed_order)과 ef_search 를 건다. 반드시 검색과 같은 트랜잭션에서 부른다. */
  public static void relaxIterativeScan(DSLContext tx) {
    tx.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
    tx.execute("SET LOCAL hnsw.ef_search = " + EF_SEARCH);
  }

  /**
   * 의미 검색 공통 실행: 한 트랜잭션을 열어 {@link #relaxIterativeScan} 을 건 뒤 <b>같은 트랜잭션의 DSL</b> 로 조회하고,
   * relaxed_order 가 흐트러뜨릴 수 있는 순서를 점수 내림차순으로 바로잡는다. {@code SET LOCAL} 은 그 트랜잭션 안에서만 유효하므로 주입된 DSL 이
   * 아니라 트랜잭션 DSL 로 조회해야 한다(문서 청크·데이터셋 검색 공용).
   */
  public static <T> List<T> search(
      DSLContext dsl,
      String sql,
      List<Object> params,
      RecordMapper<Record, T> mapper,
      ToDoubleFunction<T> score) {
    return dsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          relaxIterativeScan(tx);
          List<T> hits = new ArrayList<>(tx.fetch(sql, params.toArray()).map(mapper));
          hits.sort(Comparator.comparingDouble(score).reversed());
          return hits;
        });
  }
}
