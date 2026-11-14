package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import com.smartfirehub.vectorstore.qdrant.QdrantCollectionSpec;
import com.smartfirehub.vectorstore.qdrant.QdrantProperties;
import com.smartfirehub.vectorstore.qdrant.QdrantRowSearchIndex;
import com.smartfirehub.vectorstore.qdrant.QdrantTestContainer;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 행 검색 서비스 E2E 를 qdrant 조합으로 다시 돈다(WD-722 완료 기준 — PG 데이터 저장소 테넌트 × qdrant 의
 * SEMANTIC·KEYWORD·HYBRID·필터·swap 재구축). 시나리오는 {@link RowSearchServiceTest} 그대로다 — 서비스·동기화는 색인 구현을
 * 모르므로 같은 답이 나와야 한다. 다른 것은 의미 벡터를 세는 방법뿐이다.
 */
class QdrantRowSearchServiceTest extends RowSearchServiceTest {

  private static final QdrantProperties PROPS = QdrantTestContainer.properties(2, true);

  @DynamicPropertySource
  static void qdrant(DynamicPropertyRegistry r) {
    // 공유 테스트 DB 의 설치 종류 기록을 qdrant 로 바꿔 둔다(ADR 0002 결정 10 가드) — 끝나면 되돌린다.
    QdrantTestContainer.recordInstallType("qdrant");
    r.add("firehub.vector-store.type", () -> "qdrant");
    r.add("firehub.vector-store.qdrant.url", PROPS::url);
    r.add("firehub.vector-store.qdrant.api-key", PROPS::apiKey);
    r.add("firehub.vector-store.qdrant.shard-number", () -> "2");
    r.add("firehub.vector-store.keyword-sweep-enabled", () -> "false");
    r.add("firehub.vector-store.outbox-worker-enabled", () -> "false");
    r.add("firehub.vector-store.reconcile-sweep-enabled", () -> "false");
  }

  @AfterAll
  static void restoreInstallType() {
    QdrantTestContainer.recordInstallType("platform");
  }

  @Autowired private QdrantClient client;
  @Autowired private RowSearchIndex rowIndex;
  @Autowired private VectorStoreFeatures features;

  /**
   * 상위 정리(색인 drop)는 qdrant 조합에서 아웃박스에 행을 쌓는다. 공유 테스트 DB 의 테넌트 1 아웃박스에 남으면 뒤에 도는 시험이 클래스 순서에 따라 그 행을
   * 만나므로, 상위 정리 뒤 이 시험이 쌓은 행 아웃박스를 지운다.
   */
  @Override
  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    super.tearDown();
    inTenantFixture(
        () ->
            dsl.execute(
                "DELETE FROM vector_outbox WHERE kind IN ('ROWS_ALL_OF_DATASET', 'ROWS_DENSE_OF_DATASET')"));
  }

  @Autowired private org.jooq.DSLContext dsl;

  /** 이 데이터셋의 dense 점 수 — 테넌트 dense 컬렉션 전부(키워드 컬렉션 제외). */
  @Override
  protected long indexedVectorCount() {
    try {
      String prefix = QdrantCollectionSpec.rowsTenantPrefix(DEFAULT_TEST_TENANT_ID);
      String kw = QdrantCollectionSpec.rowsKeywordName(DEFAULT_TEST_TENANT_ID);
      long n = 0;
      for (String c : client.listCollectionsAsync().get(30, TimeUnit.SECONDS)) {
        if (!c.startsWith(prefix) || c.equals(kw)) continue;
        Filter f =
            Filter.newBuilder()
                .addMust(ConditionFactory.match(QdrantCollectionSpec.DATASET_ID, datasetId))
                .build();
        n += client.countAsync(c, f, true).get(30, TimeUnit.SECONDS);
      }
      return n;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void qdrantImplementationIsWiredAndRowSearchIsOnForPostgresDataStore() {
    assertThat(rowIndex).isInstanceOf(QdrantRowSearchIndex.class);
    assertThat(features.supportsRowSearch(DataStoreBackend.POSTGRESQL)).isTrue();
    assertThat(features.supportsRowSearch(DataStoreBackend.TIBERO)).isFalse();
    assertThat(List.of(rowIndex)).hasSize(1);
  }
}
