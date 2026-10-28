package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.pipeline.exception.TiberoUnqualifiedTableException;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.SqlAccessMode;
import com.smartfirehub.securitylevel.access.SqlAccessResult;
import com.smartfirehub.securitylevel.ai.AiHostingResolver;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * WD-81 후속 — 이상탐지 지표 SQL 의 스키마 없는 테이블 이름(Tibero)은 저장 시점에 400 안내로 나가고, 그 밖의 파싱 불가 SQL 은 기존처럼 삼켜진다.
 *
 * <p>Tibero 테넌트 통합 환경(실제 Tibero 서버)을 이 테스트에서 꾸리지 않는다 — 대신 가드를 mock 으로 두고 "가드가 {@link
 * TiberoUnqualifiedTableException} 을 던지면 전파된다 / 일반 {@link UnsafeSqlException}·{@link
 * SqlQueryException} 은 삼켜진다" 는 체커의 계약을 확인한다. 전파된 예외가 400 + 원문 문구로 나가는 것은 GlobalExceptionHandlerTest
 * 가, 미한정 이름을 실제로 던지는 쪽(가드·검증기)은 SqlValidatorTiberoTest·SqlAccessGuardTest 가 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MetricSqlAccessCheckerTest {

  private static final String UNQUALIFIED_MESSAGE =
      "테이블 참조에 스키마가 없습니다: 'orders'. Tibero 저장소에서는 genia_t5.orders 처럼 스키마와 함께 써 주세요.";

  @Mock private DatasetAccessGuard guard;
  @Mock private ClearanceResolver clearanceResolver;
  @Mock private AiHostingResolver aiHostingResolver;
  private MetricSqlAccessChecker checker;

  @BeforeEach
  void setUp() {
    when(clearanceResolver.resolve(7L)).thenReturn(mock(Clearance.class));
    checker = new MetricSqlAccessChecker(guard, clearanceResolver, aiHostingResolver);
  }

  /** 지표 한 건짜리 config 를 만든다. */
  private static Map<String, Object> configWith(String query) {
    return Map.of(
        "anomaly",
        Map.of("metrics", List.of(Map.of("id", "m1", "source", "dataset", "query", query))));
  }

  @Test
  void 미한정_테이블_예외는_삼키지_않고_문구_그대로_전파한다() {
    when(guard.requireSql(any(), anyString(), eq(SqlAccessMode.INTERACTIVE)))
        .thenThrow(new TiberoUnqualifiedTableException(UNQUALIFIED_MESSAGE));

    assertThatThrownBy(
            () ->
                checker.requireMetricQueriesAllowed(configWith("SELECT COUNT(*) FROM orders"), 7L))
        .isInstanceOf(TiberoUnqualifiedTableException.class)
        .hasMessage(UNQUALIFIED_MESSAGE);
  }

  @Test
  void 일반_UnsafeSqlException_과_SqlQueryException_은_지금처럼_삼킨다() {
    // 파싱 불가 SQL — 저장 계약 불변(폴러가 실행 전에 건너뛴다).
    when(guard.requireSql(any(), eq("SELECT 1 FROM x y z"), eq(SqlAccessMode.INTERACTIVE)))
        .thenThrow(new UnsafeSqlException("단일 SELECT 만 허용"));
    when(guard.requireSql(any(), eq("SELECT 2 FROM broken"), eq(SqlAccessMode.INTERACTIVE)))
        .thenThrow(new SqlQueryException("파싱 실패"));

    assertThatCode(() -> checker.requireMetricQueriesAllowed(configWith("SELECT 1 FROM x y z"), 7L))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> checker.requireMetricQueriesAllowed(configWith("SELECT 2 FROM broken"), 7L))
        .doesNotThrowAnyException();
  }

  @Test
  void 한정_이름_지표는_통과하고_AI_판정까지_간다() {
    when(guard.requireSql(any(), anyString(), eq(SqlAccessMode.INTERACTIVE)))
        .thenReturn(new SqlAccessResult(true, null, null, null, Set.of(1L), Set.of(), true));

    checker.requireMetricQueriesAllowed(configWith("SELECT COUNT(*) FROM genia_t5.orders"), 7L);

    verify(guard).requireAiForDatasets(any(), eq(Set.of(1L)), any());
  }
}
