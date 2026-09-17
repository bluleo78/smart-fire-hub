package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.config.TenantPipelineDataSourceRegistry;
import com.smartfirehub.global.tenant.MissingTenantScopeException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link SqlColumnProbe} 의 오류 변환 규약 단위 테스트.
 *
 * <p>실제 접속이 테넌트 롤로 이뤄지는지는 DB 가 필요하므로 {@code SqlColumnProbeSandboxTest} 가 본다.
 * 여기서는 DB 없이 검증 가능한 것만 고정한다 — probe 래핑 노출 금지(#662), 테넌트 컨텍스트 부재 시
 * fail-fast, 그리고 컨텍스트의 테넌트 id 가 그대로 풀 선택에 쓰인다는 것.
 */
@ExtendWith(MockitoExtension.class)
class SqlColumnProbeTest {

  @Mock TenantPipelineDataSourceRegistry tenantPipelineDataSources;

  /**
   * 심층 방어 검증은 이 테스트의 관심사가 아니므로 통과시킨다 — 검증 규칙 자체는 {@code SqlValidatorTest}
   * 가 본다. 목으로 두는 이유는 실물 {@code SqlValidator} 가 테넌트 컨텍스트에서 허용 스키마를 다시 묻기
   * 때문이다(컨텍스트 없는 fail-closed 케이스가 검증 단계에서 먼저 터져 의도와 다른 이유로 초록이 된다).
   */
  @Mock SqlValidator sqlValidator;

  @InjectMocks SqlColumnProbe probe;

  /** 테스트 스레드는 JUnit 이 클래스 간 재사용하므로, 앞선 테스트가 흘린 테넌트를 반드시 지운다. */
  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  /**
   * probe 는 사용자 SQL 을 {@code AS _probe LIMIT 0} 으로 감싸 실행하므로, jOOQ 예외 메시지에는 사용자가
   * 쓰지 않은 래핑 구문이 그대로 들어 있다. 사용자에게는 DB 가 준 근본 원인만 보여야 한다(#662).
   */
  @Test
  void columnsWithTypes_onProbeFailure_exposesRootCauseWithoutProbeWrapping() {
    String rootCauseMessage = "ERROR: permission denied for schema data_t2";
    org.jooq.exception.DataAccessException probeFailure =
        new org.jooq.exception.DataAccessException(
            "SQL [SELECT * FROM (SELECT 1) AS _probe LIMIT 0]; " + rootCauseMessage,
            new RuntimeException(rootCauseMessage));
    when(tenantPipelineDataSources.withTenantDsl(anyLong(), any())).thenThrow(probeFailure);

    assertThatThrownBy(
            () ->
                TenantContext.runScopedGet(
                    2L, () -> probe.columnsWithTypes("SELECT 1")))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining(rootCauseMessage)
        .hasMessageNotContaining("_probe")
        .hasMessageNotContaining("jOOQ");
  }

  /**
   * 테넌트 컨텍스트가 없으면 접속 자체를 시도하지 않는다.
   *
   * <p>여기서 조용히 기본 테넌트로 떨어지면 남의 스키마를 probe 하게 되므로 fail-closed 가 정답이다.
   *
   * <p><b>감싸지 않고 그대로 전파해야 한다.</b> {@link MissingTenantScopeException} 은 {@code
   * IllegalStateException} 을 상속하는 것이 계약이고({@code GlobalExceptionHandler} 가 409 로 변환,
   * 인바운드 디스패치가 "배선 결함" 로그를 구분), {@code ScriptExecutionException} 으로 감싸면 그 구분이
   * 사라져 "스텝이 실패했다"와 "테넌트 배선이 끊겼다"가 같은 모양이 된다. 그래서 {@code require} 는
   * {@code try} 밖에 있어야 한다 — 이 테스트가 그 배치를 고정한다.
   */
  @Test
  void columnsWithTypes_withoutTenantContext_failsClosed() {
    assertThatThrownBy(() -> probe.columnsWithTypes("SELECT 1"))
        .isInstanceOf(MissingTenantScopeException.class)
        .isNotInstanceOf(ScriptExecutionException.class);
  }

  /** 정상 경로에서 테넌트 컨텍스트의 id 가 그대로 풀 선택에 쓰이는지 고정한다. */
  @Test
  void columnsWithTypes_usesTenantIdFromContext() {
    when(tenantPipelineDataSources.withTenantDsl(anyLong(), any()))
        .thenThrow(new IllegalStateException("marker"));

    assertThatThrownBy(() -> TenantContext.runScopedGet(42L, () -> probe.columnsWithTypes("SELECT 1")))
        .isInstanceOf(ScriptExecutionException.class);

    verify(tenantPipelineDataSources).withTenantDsl(eq(42L), any());
  }
}
