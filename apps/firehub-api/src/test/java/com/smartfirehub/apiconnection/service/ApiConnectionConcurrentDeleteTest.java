package com.smartfirehub.apiconnection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.exception.ApiConnectionNotFoundException;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import com.smartfirehub.support.ConcurrentCalls;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PausedTransactionRace;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * WD-584 — 같은 API 연결을 동시에 여러 번 지우면 모두 204 가 되고 「삭제」 감사가 요청 수만큼 남던 문제.
 *
 * <p>진 쪽 요청은 순차 두 번째 삭제와 같은 404({@link ApiConnectionNotFoundException})여야 하고 감사는 실제로 지운 1건만 남아야 한다.
 * 클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 각 요청이 자기 트랜잭션으로 커밋해야 서로를 기다린다.
 */
class ApiConnectionConcurrentDeleteTest extends IntegrationTestBase {

  @Autowired private ApiConnectionService apiConnectionService;
  @Autowired private DSLContext dsl;

  /** 테스트 환경에서 외부 도메인 DNS 해석이 안 되므로 SSRF 검증을 끈다(ApiConnectionServiceTest 와 같은 설정 — 컨텍스트 공유). */
  @MockitoBean private SsrfProtectionService ssrfProtectionService;

  private Long userId;
  private String suffix;

  @BeforeEach
  void setUp() {
    suffix = Long.toString(System.nanoTime());
    userId = TenantRlsTestSupport.insertUser(dsl, "wd584_conn_");
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

  private ApiConnectionResponse create(String label) {
    return apiConnectionService.create(
        new CreateApiConnectionRequest(
            "WD584 " + suffix + " " + label,
            null,
            "API_KEY",
            Map.of("placement", "header", "headerName", "X-Key", "apiKey", "secret"),
            "https://api.example.com",
            null),
        userId);
  }

  private int deleteAudits(Long connectionId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE action_type = 'DELETE' AND resource ="
                        + " 'api_connection' AND resource_id = ?",
                    String.valueOf(connectionId))
                .get(0, Integer.class));
  }

  @Test
  void 같은_연결을_6건_동시에_지우면_1건만_성공하고_나머지는_404다() throws Exception {
    ApiConnectionResponse conn = create("six");

    List<Throwable> results =
        ConcurrentCalls.run(
            6, DEFAULT_TEST_TENANT_ID, () -> apiConnectionService.delete(conn.id(), userId));

    assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
    assertThat(results.stream().filter(Objects::nonNull).toList())
        .hasSize(5)
        .allMatch(ApiConnectionNotFoundException.class::isInstance);
    assertThat(deleteAudits(conn.id())).isEqualTo(1);
  }

  @Test
  void 지운_트랜잭션이_커밋되기_전_같은_삭제는_기다렸다가_404이고_감사는_1건이다() throws Exception {
    ApiConnectionResponse conn = create("paused");

    PausedTransactionRace.Outcome<Void> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> apiConnectionService.delete(conn.id(), userId),
            () -> {
              apiConnectionService.delete(conn.id(), userId);
              return null;
            });

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError())
        .isInstanceOf(ApiConnectionNotFoundException.class)
        .hasMessage("API 연결을 찾을 수 없습니다: " + conn.id());
    assertThat(deleteAudits(conn.id())).isEqualTo(1);
  }

  @Test
  void 순차로_두_번_지우면_두_번째는_404다() {
    ApiConnectionResponse conn = create("seq");

    apiConnectionService.delete(conn.id(), userId);

    assertThatThrownBy(() -> apiConnectionService.delete(conn.id(), userId))
        .isInstanceOf(ApiConnectionNotFoundException.class);
    assertThat(deleteAudits(conn.id())).isEqualTo(1);
  }
}
