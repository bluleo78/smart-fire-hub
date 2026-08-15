package com.smartfirehub.apiconnection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.smartfirehub.apiconnection.dto.TestConnectionResponse;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import com.smartfirehub.apiconnection.service.ApiConnectionHealthCheckScheduler;
import com.smartfirehub.apiconnection.service.ApiConnectionNotifier;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * api_connection 도메인의 테넌트 배선 검증 (P2-b Task 6).
 *
 * <p>두 가지를 본다.
 *
 * <ul>
 *   <li>{@code ApiConnectionHealthCheckScheduler.runOnce()} 가 ACTIVE 테넌트를 순회하는가 — 순회하지
 *       않으면 {@code @Scheduled} 스레드에 테넌트가 없어 RLS 가 대상을 전부 차단하고 UP/DOWN 알림이
 *       예외도 로그도 없이 영구히 누락된다.
 *   <li>{@code ApiConnectionService.testConnection} 의 조회가 <b>프록시를 통과해</b> 트랜잭션 안에서
 *       일어나고, 외부 HTTP 호출은 <b>트랜잭션 밖</b>에 남는가 — 조회를 자기호출하면 프록시가 우회돼
 *       트랜잭션 경계가 사라지고, 반대로 {@code testConnection} 에 {@code @Transactional} 을 붙이면
 *       5초짜리 HTTP 가 커넥션을 물어 풀이 고갈된다.
 * </ul>
 *
 * <p><b>클래스 레벨 {@code @Transactional} 이 없다 — 의도된 것이다.</b> 테스트가 트랜잭션을 열면 GUC
 * 가 그 트랜잭션에서 공급돼 프로덕션의 배선 누락을 구조적으로 가린다. 픽스처만
 * {@link TenantRlsTestSupport#runInTenantTransaction} 으로 감싸고, 검증 대상 호출은 트랜잭션 밖에
 * 남긴다(선례: {@code dashboard/job/PipelineExecutionTtlJobTest},
 * {@code pipeline/TriggerSchedulerTenantTest}).
 *
 * <p>{@link ApiConnectionNotifier} 는 목이다. {@code runOnce()} 는 공유 테스트 DB 의 <b>모든</b>
 * 테넌트를 훑으므로, 실 빈이면 다른 테스트가 심어둔 연결의 상태 전환으로 감사 로그와 SSE 브로드캐스트가
 * 발생한다.
 */
class ApiConnectionTenantTest extends IntegrationTestBase {

  @Autowired private ApiConnectionHealthCheckScheduler scheduler;
  @Autowired private DSLContext dsl;
  @Autowired private EncryptionService encryptionService;
  @Autowired private PlatformTransactionManager transactionManager;

  /** 스파이 — 테스트 1 은 HTTP 를 피하려 스텁하고, 테스트 2 는 실제 본문을 그대로 돌린다. */
  @MockitoSpyBean private ApiConnectionService apiConnectionService;

  /** 스파이 — 호출 시점의 트랜잭션 상태를 관측하기 위한 계측 지점. */
  @MockitoSpyBean private ApiConnectionRepository repository;

  @MockitoBean private ApiConnectionNotifier notifier;

  private TransactionTemplate tx;

  private Long tenantA;
  private Long tenantB;
  private Long userA;
  private Long userB;
  private Long connA;
  private Long connB;

  /** findById 호출 시점에 열려 있던 트랜잭션의 이름(없으면 null). */
  private final List<String> findByIdTxNames = new CopyOnWriteArrayList<>();

  /** updateHealthStatus 호출 시점에 열려 있던 트랜잭션의 이름. */
  private final List<String> updateHealthStatusTxNames = new CopyOnWriteArrayList<>();

  @BeforeEach
  void seedTwoTenants() {
    tx = new TransactionTemplate(transactionManager);

    // tenant·user 는 테넌트 경계 위의 전역 테이블(RLS 없음)이라 컨텍스트 없이 만들 수 있다.
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "apiconn-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "apiconn-b");
    userA = TenantRlsTestSupport.insertUser(dsl, "apiconn_a_");
    userB = TenantRlsTestSupport.insertUser(dsl, "apiconn_b_");

    // 도메인 픽스처는 각 테넌트 컨텍스트의 트랜잭션 안에서 만든다 — tenant_id DEFAULT 가 GUC 에서 채워진다.
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantA,
        () -> {
          connA = insertConnection(userA, "Tenant A Conn");
        });
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantB,
        () -> {
          connB = insertConnection(userB, "Tenant B Conn");
        });

    findByIdTxNames.clear();
    updateHealthStatusTxNames.clear();

    // 계측: 실제 메서드는 그대로 실행하되, 호출 시점의 트랜잭션 이름만 기록한다.
    // 스파이는 리포지토리의 트랜잭션 프록시 <b>안쪽</b>에 놓이므로, 여기서 보이는 이름은
    // "이 호출을 실제로 감싸고 있는 가장 바깥 트랜잭션"의 이름이다. 앰비언트 트랜잭션이 이미
    // 있었으면 REQUIRED 로 합류해 그 이름이 그대로 남고, 없었으면 리포지토리 자신의 이름이 된다.
    // 이 차이가 "조회는 서비스 레이어 트랜잭션 안, HTTP 는 밖" 을 구분하는 판별자다.
    doAnswer(
            inv -> {
              findByIdTxNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
              return inv.callRealMethod();
            })
        .when(repository)
        .findById(anyLong());
    doAnswer(
            inv -> {
              updateHealthStatusTxNames.add(
                  TransactionSynchronizationManager.getCurrentTransactionName());
              return inv.callRealMethod();
            })
        .when(repository)
        .updateHealthStatus(anyLong(), anyString(), any(), any());
  }

  @AfterEach
  void cleanUp() {
    // 롤백이 없으므로 심은 행을 직접 지운다. RLS 가 걸리면 DELETE 도 테넌트 스코프이므로
    // 각자의 컨텍스트에서 지운다.
    TenantRlsTestSupport.runInTenantTransaction(tx, tenantA, () -> deleteConnection(connA));
    TenantRlsTestSupport.runInTenantTransaction(tx, tenantB, () -> deleteConnection(connB));
    TenantRlsTestSupport.deleteUser(dsl, userA);
    TenantRlsTestSupport.deleteUser(dsl, userB);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
  }

  @Test
  @DisplayName("헬스체크 스케줄러는 ACTIVE 테넌트를 순회해 양쪽 테넌트의 연결을 모두 점검한다")
  void runOnce_iteratesActiveTenants() {
    // 실제 HTTP 를 타지 않도록 스텁하고, 호출 시점의 테넌트 컨텍스트를 함께 기록한다.
    List<String> processed = new CopyOnWriteArrayList<>();
    doAnswer(
            inv -> {
              processed.add(inv.getArgument(0) + "@" + TenantContext.get());
              return new TestConnectionResponse(
                  true, 200, 1L, null, "http://stub", null, Map.of(), null);
            })
        .when(apiConnectionService)
        .testConnection(anyLong());

    // 검증 대상 호출은 트랜잭션 밖이다 — 스케줄러 스스로 컨텍스트와 트랜잭션을 얻어야 한다.
    scheduler.runOnce();

    // 양방향 단언: 두 테넌트의 연결이 각각 자기 테넌트 컨텍스트에서 처리돼야 한다.
    // 순회가 없으면 컨텍스트가 null 이라 두 단언이 모두 깨진다.
    assertThat(processed)
        .as("테넌트 A 의 연결이 테넌트 A 컨텍스트에서 처리돼야 한다")
        .contains(connA + "@" + tenantA);
    assertThat(processed)
        .as("테넌트 B 의 연결이 테넌트 B 컨텍스트에서 처리돼야 한다")
        .contains(connB + "@" + tenantB);
  }

  @Test
  @DisplayName("testConnection 의 조회는 프록시를 통과해 트랜잭션 안에서, HTTP 는 트랜잭션 밖에서 일어난다")
  void testConnection_readsInTransaction_httpOutside() {
    // 컨텍스트만 세우고 트랜잭션은 열지 않는다 — 운영의 스케줄러/비동기 경로와 동일한 조건.
    TenantContext.set(tenantA);
    TestConnectionResponse result;
    try {
      result = apiConnectionService.testConnection(connA);
    } finally {
      TenantContext.clear();
    }

    // 127.0.0.1:1 은 즉시 연결 거부된다 — 조회가 성공해 HTTP 단계까지 갔다는 증거이기도 하다.
    assertThat(result).isNotNull();
    assertThat(result.ok()).isFalse();

    // 조회 두 번(getById / getDecryptedAuthConfig)이 모두 reader 프록시가 연 트랜잭션 안에서 일어난다.
    // 자기호출로 되돌리면 앰비언트 트랜잭션이 없어 이름이 리포지토리 자신의 것이 되고 단언이 깨진다.
    assertThat(findByIdTxNames)
        .as("testConnection 이 조회를 두 번 해야 한다(getById, getDecryptedAuthConfig)")
        .hasSize(2);
    assertThat(findByIdTxNames)
        .as("조회는 ApiConnectionReader 가 연 트랜잭션 안에서 일어나야 한다 — 자기호출이면 프록시를 우회한다")
        .allSatisfy(
            txName ->
                assertThat(txName)
                    .isNotNull()
                    .startsWith("com.smartfirehub.apiconnection.service.ApiConnectionReader."));

    // HTTP 직후의 DB 쓰기는 리포지토리 <b>자신의</b> 트랜잭션에서 일어나야 한다. 이름이 서비스
    // 메서드의 것이면, 그 트랜잭션이 HTTP 구간까지 감싸고 있었다는 뜻이다
    // (= testConnection 에 @Transactional 이 붙어 커넥션을 5초간 물고 있었다는 뜻).
    assertThat(updateHealthStatusTxNames)
        .as("updateHealthStatus 가 한 번 호출돼야 한다")
        .hasSize(1);
    assertThat(updateHealthStatusTxNames)
        .as("HTTP 구간을 감싸는 서비스 트랜잭션이 있으면 안 된다 — 커넥션 풀 점유가 재발한다")
        .containsExactly(
            "com.smartfirehub.apiconnection.repository.ApiConnectionRepository.updateHealthStatus");
  }

  // ── 픽스처 헬퍼 ────────────────────────────────────────────────────────────

  /**
   * 헬스체크 대상 api_connection 한 건을 만든다.
   *
   * <p>base_url 은 즉시 연결이 거부되는 주소를 쓴다 — 테스트가 실제 외부 네트워크를 타거나 5초
   * 타임아웃을 기다리지 않게 하기 위함이다. auth_type 은 CHECK 제약이 허용하는 값만 쓸 수 있다
   * (API_KEY / BEARER / OAUTH2).
   */
  private Long insertConnection(Long ownerUserId, String connName) {
    String encrypted = encryptionService.encrypt("{\"apiKey\":\"dummy-key\"}");
    return dsl.insertInto(table(name("api_connection")))
        .set(field(name("name"), String.class), connName + "-" + System.nanoTime())
        .set(field(name("auth_type"), String.class), "API_KEY")
        .set(field(name("auth_config"), String.class), encrypted)
        .set(field(name("created_by"), Long.class), ownerUserId)
        .set(field(name("base_url"), String.class), "http://127.0.0.1:1")
        .set(field(name("health_check_path"), String.class), "/health")
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private void deleteConnection(Long id) {
    if (id != null) {
      dsl.deleteFrom(table(name("api_connection")))
          .where(field(name("id"), Long.class).eq(id))
          .execute();
    }
  }
}
