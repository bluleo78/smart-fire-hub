package com.smartfirehub.graphreview;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.graphreview.repository.ReviewItemRepository;
import com.smartfirehub.graphreview.repository.mybatis.MyBatisReviewItemRepository;
import com.smartfirehub.graphreview.repository.mybatis.ReviewItemMapper;
import com.smartfirehub.support.tibero.TiberoPlatformPoc;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * ReviewItemRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-487 PoC).
 *
 * <p>{@code TIBERO_TEST_HOST} 가 없으면 클래스째 건너뛰고, 드라이버 jar 가 없으면 {@link TiberoPlatformPoc#shared()} 의
 * 가정으로 건너뛴다(pre-commit 훅 초록 유지). PoC 계정·스키마는 JVM 공용이라 행 정리는 기반 클래스의 {@link #cleanupFixtures()} 가
 * 맡는다. 구현은 앱과 같은 클래스를 {@code new} 로 만들고, 매퍼는 Tibero 팩토리(databaseId=tibero)에서, 트랜잭션은 Tibero 관리자로 감싼
 * 프록시에서 얻는다. 픽스처는 같은 Tibero 계정의 JdbcTemplate 으로 다시 구현한다.
 *
 * <p>스프링 컨텍스트(PG)는 기반 클래스 때문에 함께 뜨지만 이 클래스는 PG 를 만지지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "TIBERO_TEST_HOST", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisReviewItemRepositoryContractTest extends ReviewItemRepositoryContractTest {

  private TiberoPlatformPoc poc;
  private ReviewItemRepository repository;

  /** JVM 공용 PoC 하네스에서 Tibero 트랜잭션 프록시로 감싼 구현을 준비한다. */
  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformPoc.shared();
    repository =
        poc.transactional(
            ReviewItemRepository.class,
            new MyBatisReviewItemRepository(poc.mapper(ReviewItemMapper.class)));
  }

  @Override
  protected ReviewItemRepository repository() {
    return repository;
  }

  @Override
  protected void cleanupFixtures() {
    // Tibero 스키마에는 FK 가 없어 순서는 상관없지만 PG 판과 같은 순서로 지운다.
    poc.jdbc().update("DELETE FROM graph_review_item WHERE dedupe_key LIKE 'Test%'");
    poc.jdbc().update("DELETE FROM \"USER\" WHERE username LIKE 'testdecider%'");
  }

  /** Tibero 트랜잭션 + 기본 테넌트 컨텍스트(비동기 스레드에는 ThreadLocal 이 없으므로 여기서 세운다). */
  @Override
  protected <T> T inTenantTransaction(Supplier<T> work) {
    return poc.tx().execute(s -> TenantContext.runScopedGet(DEFAULT_TEST_TENANT_ID, work));
  }

  @Override
  protected long insertDecider() {
    return poc.insertReturningId(
        "INSERT INTO \"USER\"(username, password, name, email)"
            + " VALUES ('testdecider','x','T','testdecider@example.com')");
  }

  @Override
  protected String dedupeKeyOf(long id) {
    return poc.jdbc()
        .queryForObject("SELECT dedupe_key FROM graph_review_item WHERE id = ?", String.class, id);
  }
}
