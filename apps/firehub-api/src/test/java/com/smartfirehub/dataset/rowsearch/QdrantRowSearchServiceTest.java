package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import com.smartfirehub.vectorstore.qdrant.QdrantCollectionSpec;
import com.smartfirehub.vectorstore.qdrant.QdrantProperties;
import com.smartfirehub.vectorstore.qdrant.QdrantRowSearchIndex;
import com.smartfirehub.vectorstore.qdrant.QdrantTestContainer;
import com.smartfirehub.vectorstore.qdrant.QdrantVectorStoreLifecycle;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

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
  @Autowired private QdrantVectorStoreLifecycle lifecycle;
  @Autowired private VectorStoreFeatures features;

  /**
   * 상위 준비보다 <b>먼저</b>, 다른 시험이 지운 컬렉션을 앱 빈이 다시 만들게 한다(WD-788).
   *
   * <p>같은 JVM 의 다른 Qdrant 시험(예: {@code QdrantPlatformSearchIntegrationTest})은 공유 Qdrant 의 컬렉션을
   * <b>모두</b> 지운다 — 컬렉션 이름이 고정이라 「제 것만」 지울 수 없다. 그런데 앱 빈({@link QdrantVectorStoreLifecycle})은 한 번
   * 준비한 컬렉션 이름을 {@code ready} 에 기억해 다시 확인하지 않으므로, 캐시된 이 컨텍스트로 돌아오면 행 색인이 사라진 행 컬렉션에 쓰다 「collection
   * not found」로 실패한다(새 lifecycle 로 기본 컬렉션만 다시 만들어서는 행 컬렉션·빈의 기억이 그대로라 부족하다 — 코드 리뷰 지적).
   *
   * <p>그래서 매 시험 전에 빈이 기억하는 컬렉션이 실제로 모두 있는지 목록 한 번으로 보고, 하나라도 없을 때만 그 기억을 비우고 빈의 {@code prepare()} 로
   * 기본 컬렉션을 다시 만든다 — 행 컬렉션은 기억이 비었으니 다음 쓰기의 {@code ensureRowCollections} 가 다시 만든다. 지워지지 않았으면 목록 조회
   * 한 번만 든다(매번 전체 준비를 돌리지 않게). 운영 코드에 시험 전용 「기억 비우기」를 두지 않으려고 시험이 {@link ReflectionTestUtils} 로
   * 비운다.
   *
   * <p>별도 {@code @BeforeEach} 가 아니라 상위 {@code setUp} 을 재정의한 이유: JUnit 은 상위 클래스의 {@code @BeforeEach}
   * 를 하위 것보다 먼저 부르는데, 상위 준비가 이미 행을 색인({@code sync.sync})하므로 하위 {@code @BeforeEach} 로는 늦다.
   */
  @Override
  @BeforeEach
  void setUp() {
    restoreCollectionsWipedByOtherTests();
    super.setUp();
  }

  /** 앱 빈이 준비됐다고 기억하는 컬렉션 중 실제로 없는 것이 있으면 기억을 비우고 다시 준비한다(WD-788, {@link #setUp} 주석). */
  @SuppressWarnings("unchecked")
  private void restoreCollectionsWipedByOtherTests() {
    // WD-796: 필드 이름이 바뀌면 getField 가 그 이름을 담은 IllegalArgumentException 을 던져 시험이 바로 실패한다
    // (ready 는 final newKeySet 이라 null 이 되지 않아, 조용히 아무것도 하지 않는 경우는 없다).
    Set<String> ready = (Set<String>) ReflectionTestUtils.getField(lifecycle, "ready");
    try {
      List<String> existing = client.listCollectionsAsync().get(30, TimeUnit.SECONDS);
      if (existing.containsAll(ready)) return;
    } catch (Exception e) {
      throw new IllegalStateException("Qdrant 컬렉션 목록을 읽지 못했습니다", e);
    }
    ready.clear();
    lifecycle.prepare();
  }

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

  /**
   * 다른 시험이 행 컬렉션을 지운 뒤에도 앱 빈이 행 컬렉션을 다시 만든다(WD-788 회귀, WD-796 — 공유 Qdrant 의 다른 컬렉션은 건드리지 않는다). 빈의
   * 「준비됨」 기억을 비우지 않으면 {@code ensureRowCollections} 가 기억만 보고 돌아가 행 컬렉션이 없는 채로 남는다.
   */
  @Test
  void rowCollectionIsRecreatedAfterAnotherTestDeletedIt() throws Exception {
    String keyword = QdrantCollectionSpec.rowsKeywordName(DEFAULT_TEST_TENANT_ID);
    // setUp 의 색인으로 행 키워드 컬렉션이 만들어졌고 빈이 그것을 기억한다
    assertThat(client.collectionExistsAsync(keyword).get(30, TimeUnit.SECONDS)).isTrue();

    // WD-796: 전체 삭제 대신 이 시험의 컬렉션만 지운다(공유 Qdrant 의 다른 시험 컬렉션 보호)
    client.deleteCollectionAsync(keyword).get(30, TimeUnit.SECONDS);
    restoreCollectionsWipedByOtherTests();
    lifecycle.ensureRowCollections(DEFAULT_TEST_TENANT_ID, 1);

    assertThat(client.collectionExistsAsync(keyword).get(30, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void qdrantImplementationIsWiredAndRowSearchIsOnForPgAndTiberoDataStores() {
    assertThat(rowIndex).isInstanceOf(QdrantRowSearchIndex.class);
    assertThat(features.supportsRowSearch(DataStoreBackend.POSTGRESQL)).isTrue();
    assertThat(features.supportsRowSearch(DataStoreBackend.TIBERO)).isTrue(); // WD-723
    assertThat(features.supportsRowSearch(DataStoreBackend.MYSQL)).isFalse();
    assertThat(List.of(rowIndex)).hasSize(1);
  }
}
