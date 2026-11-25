package com.smartfirehub.apiconnection.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.exception.ApiConnectionNameAlreadyExistsException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * WD-1107 — 대소문자만 다른 이름("Sales"·"sales")을 동시에 만들면 둘 다 통과하던 문제. 이름 잠금 아래에서 검사하므로 성공은 1건, 나머지는 409 여야
 * 한다. 클래스 레벨 트랜잭션을 쓰지 않는다 — 각 요청이 자기 트랜잭션으로 커밋해야 서로를 기다린다.
 */
class ApiConnectionNameConcurrencyTest extends IntegrationTestBase {

  @Autowired private ApiConnectionService apiConnectionService;
  @Autowired private DSLContext dsl;

  /** 외부 도메인 DNS 해석이 안 되므로 SSRF 검증을 끈다(다른 API 연결 시험과 같은 설정). */
  @MockitoBean private SsrfProtectionService ssrfProtectionService;

  private Long userId;

  @BeforeEach
  void setUp() {
    userId = TenantRlsTestSupport.insertUser(dsl, "wd1107_conn_");
  }

  @AfterEach
  void tearDown() {
    inTenantFixture(
        () -> {
          dsl.execute("DELETE FROM api_connection WHERE created_by = ?", userId);
          dsl.execute("DELETE FROM audit_log WHERE user_id = ?", userId);
        });
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }

  private void create(String name) {
    apiConnectionService.create(
        new CreateApiConnectionRequest(
            name,
            null,
            "API_KEY",
            Map.of("placement", "header", "headerName", "X-Key", "apiKey", "secret"),
            "https://api.example.com",
            null),
        userId);
  }

  @Test
  void concurrentCreate_caseVariants_onlyOneSucceeds() throws Exception {
    String base = "Sales" + System.nanoTime();
    String[] names = {base, base.toLowerCase()};
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Future<Throwable>> futures = new ArrayList<>();
    try {
      for (String n : names) {
        futures.add(
            pool.submit(
                () -> {
                  TenantContext.set(DEFAULT_TEST_TENANT_ID);
                  try {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    create(n);
                    return null;
                  } catch (Throwable e) {
                    return e;
                  } finally {
                    TenantContext.clear();
                  }
                }));
      }
      // 두 요청이 모두 시작선에 섰는지 확인해야 경합이 실제로 맞춰졌다고 볼 수 있다.
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      List<Throwable> results = new ArrayList<>();
      for (Future<Throwable> f : futures) {
        results.add(f.get(120, TimeUnit.SECONDS));
      }
      assertThat(results.stream().filter(r -> r == null).count()).isEqualTo(1);
      assertThat(results.stream().filter(r -> r != null).toList())
          .hasSize(1)
          .allMatch(ApiConnectionNameAlreadyExistsException.class::isInstance);
    } finally {
      pool.shutdownNow();
    }
    int rows =
        inTenantFixture(
            () ->
                dsl.fetchOne("SELECT count(*) FROM api_connection WHERE created_by = ?", userId)
                    .get(0, Integer.class));
    assertThat(rows).isEqualTo(1);
  }
}
