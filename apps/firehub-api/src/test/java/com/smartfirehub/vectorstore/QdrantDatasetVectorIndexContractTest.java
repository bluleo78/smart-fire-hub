package com.smartfirehub.vectorstore;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.vectorstore.qdrant.QdrantClientConfig;
import com.smartfirehub.vectorstore.qdrant.QdrantDatasetVectorIndex;
import com.smartfirehub.vectorstore.qdrant.QdrantProperties;
import com.smartfirehub.vectorstore.qdrant.QdrantTestContainer;
import com.smartfirehub.vectorstore.qdrant.QdrantVectorStoreLifecycle;
import com.smartfirehub.vectorstore.repository.jooq.JooqSearchCandidateRepository;
import io.qdrant.client.QdrantClient;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link DatasetVectorIndexContractTest} 를 Qdrant 구현으로 실행한다(WD-720). pgvector 와 같은 시나리오(의미·키워드 순위,
 * 저장 유형 필터, 벡터 없는 행 제외)를 Testcontainers Qdrant 에서 돌린다.
 *
 * <p>플랫폼 DB 는 테스트 트랜잭션 안에 시드되고, 사후 확인(jOOQ 후보 확인)도 같은 트랜잭션에 합류해 그 행을 본다. 시드는 포트 자신의 쓰기 메서드({@code
 * upsertDense}·{@code upsertKeyword})로 한다.
 */
class QdrantDatasetVectorIndexContractTest extends DatasetVectorIndexContractTest {

  private static QdrantProperties props;
  private static QdrantClient client;

  @Autowired private JooqSearchCandidateRepository candidates;
  @Autowired private com.smartfirehub.vectorstore.repository.jooq.JooqVectorOutboxRepository outbox;

  @Autowired
  private com.smartfirehub.vectorstore.repository.jooq.JooqVectorMarkerRepository markers;

  private QdrantDatasetVectorIndex index;

  @BeforeAll
  static void startClient() {
    props = QdrantTestContainer.properties(2, true);
    client = QdrantClientConfig.create(props);
  }

  @AfterAll
  static void closeClient() {
    client.close();
  }

  /** 시험마다 빈 저장소에서 컬렉션을 새로 만든다. */
  @BeforeEach
  void emptyStore() throws Exception {
    QdrantTestContainer.deleteAllCollections(client);
    new QdrantVectorStoreLifecycle(client, props).prepare();
    index = new QdrantDatasetVectorIndex(client, props, candidates, outbox, markers);
  }

  @Override
  protected DatasetVectorIndex repository() {
    return index;
  }

  /** 키워드는 늘, dense 는 있을 때 포트로 쓴다. */
  @Override
  protected void seedIndex(long datasetId, String sourceText, float[] vector) {
    if (vector != null) {
      index.upsertDense(SEED_SPACE, List.of(datasetId), List.of(vector));
    }
    index.upsertKeyword(
        TenantContext.require("시드"), List.of(KeywordSource.dataset(datasetId, sourceText)));
  }
}
