package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;

import com.smartfirehub.global.config.TenantPipelineDataSourceRegistry;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link TenantPipelineDataSourceRegistry} 의 캐싱·LRU 축출·실제 롤 접속을 검증한다.
 *
 * <p>세 번째 단언(테넌트 1 이 실제로 {@code pipeline_executor_t1} 롤로 접속하는지)이 핵심이다 — 이게
 * 없으면 풀만 만들고 롤이 틀려도 이 테스트가 통과해 버린다.
 */
class TenantPipelineDataSourceRegistryTest extends IntegrationTestBase {

  // 실제 tenant 테이블에 존재하지 않는 가짜 id. 풀 생성 자체는 DB 접속 없이 지연 초기화되므로
  // (HikariDataSource#setInitializationFailTimeout(-1)) 실제 tenant 로 등록되지 않아도 LRU/축출
  // 검증에는 안전하다 — 이 시나리오는 어떤 가짜 풀에도 실제 쿼리를 날리지 않는다.
  private static final long FAKE_TENANT_A = 90001L;
  private static final long FAKE_TENANT_B = 90002L;
  private static final long FAKE_TENANT_C = 90003L;
  private static final long FAKE_TENANT_D = 90004L;

  @Autowired private TenantPipelineDataSourceRegistry registry;

  @Test
  @DisplayName("같은 테넌트는 같은 풀, 상한 초과는 LRU 축출+close, 테넌트1은 실제로 pipeline_executor_t1 로 접속한다")
  void dslForWithoutLeaseCachesEvictsAndConnectsAsTenantRole() {
    // (a) 같은 테넌트를 두 번 호출하면 같은 DSLContext 를 돌려준다 — 풀을 매번 새로 만들지 않는다.
    DSLContext firstCallForA = registry.withTenantDsl(FAKE_TENANT_A, dsl -> dsl);
    DSLContext secondCallForA = registry.withTenantDsl(FAKE_TENANT_A, dsl -> dsl);
    assertThat(secondCallForA).isSameAs(firstCallForA);

    // application-test.yml 의 app.pipeline.tenant-pool.max-pools=3. 삽입 순서(=LRU 순서, A 는
    // 재접근했지만 이후로는 건드리지 않는다)는 A(가장 오래됨) → B → C(가장 최근) 가 된다.
    registry.withTenantDsl(FAKE_TENANT_B, dsl -> dsl);
    registry.withTenantDsl(FAKE_TENANT_C, dsl -> dsl);
    assertThat(registry.poolCount()).isEqualTo(3);

    // (b) 상한을 넘는 네 번째 테넌트(D)를 요청하면 가장 오래 전에 쓰인 A 가 축출되고 close() 된다.
    // 풀 개수는 상한(3)을 그대로 유지한다.
    registry.withTenantDsl(FAKE_TENANT_D, dsl -> dsl);
    assertThat(registry.poolCount()).isEqualTo(3);

    // 축출 전에 잡아 둔 A 의 DSLContext 로 쿼리를 시도하면, 닫힌 HikariDataSource 에서 커넥션을
    // 얻지 못해 실패해야 한다 — 이 실패가 "실제로 close() 됐다"는 증거다. 참조가 evicted 이후에도
    // 살아 있다는 사실 자체가 "클로저가 축출된 풀을 붙들면 누수"라는 경고를 재현한다.
    assertThatThrownBy(() -> firstCallForA.selectOne().fetch())
        .as("축출된 풀은 close() 되어 더 이상 커넥션을 내주지 않아야 한다")
        .isInstanceOf(RuntimeException.class);

    // (c) 테넌트 1 의 DSLContext 는 실제로 pipeline_executor_t1 롤로 접속한다 — 풀만 만들고 롤이
    // 틀려도 통과하는 공허한 테스트가 되지 않도록 하는 핵심 단언. RolePasswordSyncCallback 이 앱
    // 기동(=이 테스트 클래스의 Spring 컨텍스트 부팅) 시 이미 test yml 의 role-password-secret 으로
    // pipeline_executor_t1 비밀번호를 맞춰 둔 상태라야 이 접속이 성공한다.
    String currentUser =
        registry.withTenantDsl(1L, dsl -> dsl).select(field("current_user", String.class)).fetchOne(0, String.class);
    assertThat(currentUser).isEqualTo("pipeline_executor_t1");
  }

  /**
   * 대여 중({@link TenantPipelineDataSourceRegistry#withTenantDsl}) 인 풀은 축출되지 않는다 —
   * 코드리뷰 지적 3 의 회귀 가드.
   *
   * <p>왜 필요한가: LRU 최신성은 {@code dslForWithoutLease}(스텝 시작) 에서만 갱신되므로, 오래 도는 스텝의 풀은
   * <b>바쁜 채로 늙는다</b>. 사용 카운트를 보지 않으면 그 풀이 축출 대상으로 뽑혀 {@code close()} 되고,
   * 진행 중인 문장이 엉뚱한 {@code ScriptExecutionException} 으로 죽는다.
   *
   * <p>위의 (b) 단언은 <b>축출된</b> 컨텍스트가 실패하는지만 보므로 이 경우를 덮지 못한다.
   *
   * <p><b>단언을 개수가 아니라 동일성으로 하는 이유(첫 시도가 실패해서 알게 된 것).</b> 대여 중인
   * 풀이 <i>하나</i> 뿐이면 축출할 자유로운 풀이 항상 남아 있어 상한은 그대로 지켜진다 — 즉
   * "개수가 상한을 넘는다"는 단언은 틀렸다(상한 초과는 <b>모든</b> 풀이 대여 중일 때만 일어난다).
   * 이 가드가 실제로 지키는 것은 개수가 아니라 <b>그 풀이 살아남는다</b>는 것이므로, 대여했던
   * {@link DSLContext} 인스턴스가 축출 뒤에도 <b>같은 인스턴스로</b> 남아 있는지를 본다. 축출됐다면
   * 레지스트리가 새 풀·새 컨텍스트를 만들어 다른 인스턴스가 돌아온다.
   */
  @Test
  @DisplayName("대여 중인 풀은 축출되지 않는다(진행 중 작업 보호)")
  void withTenantDslProtectsLeasedPoolFromEviction() {
    long leased = 90101L;

    DSLContext leasedDslContext =
        registry.withTenantDsl(
            leased,
            leasedDsl -> {
              // 대여 구간 안에서 상한(3)을 여러 번 넘길 만큼 다른 테넌트 풀을 만든다.
              // 사용 카운트가 0 인 풀들만 축출돼야 하고 leased 는 건너뛰어져야 한다.
              registry.withTenantDsl(90102L, dsl -> dsl);
              registry.withTenantDsl(90103L, dsl -> dsl);
              registry.withTenantDsl(90104L, dsl -> dsl);
              registry.withTenantDsl(90105L, dsl -> dsl);
              return leasedDsl;
            });

    // 축출을 건너뛰었다면 같은 풀이 그대로 남아 같은 DSLContext 를 돌려준다.
    // 사용 카운트를 보지 않는 구현에서는 leased 가 가장 오래된 항목이라 가장 먼저 축출된다.
    DSLContext afterEviction = registry.withTenantDsl(leased, dsl -> dsl);
    assertThat(afterEviction)
        .as("대여 중이던 풀은 축출되지 않아야 하므로 같은 DSLContext 인스턴스가 유지된다")
        .isSameAs(leasedDslContext);
  }
}
