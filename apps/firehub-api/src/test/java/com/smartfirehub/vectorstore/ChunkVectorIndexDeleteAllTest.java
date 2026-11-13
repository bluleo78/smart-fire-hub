package com.smartfirehub.vectorstore;

import com.smartfirehub.vectorstore.off.OffChunkVectorIndex;
import com.smartfirehub.vectorstore.repository.jooq.PgChunkVectorIndex;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 청크 색인의 「전부 지우기」(문서 삭제·재적재·데이터셋 삭제의 명시 삭제, ADR 0002 결정 6)는 pgvector·꺼짐 구현 모두 할 일이 없다. 삭제 경로는 기능 가드
 * 밖이라 꺼짐 구현도 예외를 던지면 안 된다. DB 에 닿지 않으므로 의존성 없이 만든다.
 */
class ChunkVectorIndexDeleteAllTest {

  @Test
  void pgvector_와_꺼짐_구현_모두_예외_없이_끝난다() {
    for (ChunkVectorIndex index :
        new ChunkVectorIndex[] {new PgChunkVectorIndex(null, null), new OffChunkVectorIndex()}) {
      index.deleteAllOfDocument(1L, 9L);
      index.deleteAllOfDatasets(1L, List.of(3L));
      index.deleteAllOfDatasets(1L, List.of());
    }
  }
}
