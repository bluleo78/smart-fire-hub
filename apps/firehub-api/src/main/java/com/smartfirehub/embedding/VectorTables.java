package com.smartfirehub.embedding;

import java.util.function.Function;
import org.jooq.DSLContext;

/**
 * 차원별 벡터 테이블 공용 조작. 문서 청크·데이터셋 저장소가 같은 루프를 복제하던 것을 모은다 — 두 쪽은 테이블명을
 * 고르는 함수({@link EmbeddingDimension#chunkTable} / {@link EmbeddingDimension#datasetTable})만 다르다. 테이블명
 * 식별자는 여전히 {@link EmbeddingDimension} 에서만 나온다(설정·요청값을 이어 붙이지 않는다).
 */
public final class VectorTables {

  private VectorTables() {}

  /**
   * {@code keep} 이 아닌 차원 테이블에서 {@code tenantId} 의 행을 지운다(재임베딩 완료 뒤 잔여 정리). RLS 만으로도
   * 테넌트 범위지만 {@code WHERE tenant_id = ?} 를 명시한다 — 소유자 커넥션에서 불려도 남의 행을 지우지 않게(조건
   * 없는 DELETE 금지 규율). 지운 행 수를 돌려준다.
   */
  public static int deleteOtherDimensions(
      DSLContext dsl, EmbeddingDimension keep, Function<EmbeddingDimension, String> table, long tenantId) {
    int deleted = 0;
    for (EmbeddingDimension d : EmbeddingDimension.values()) {
      if (d == keep) continue;
      deleted += dsl.execute("DELETE FROM " + table.apply(d) + " WHERE tenant_id = ?", tenantId);
    }
    return deleted;
  }
}
