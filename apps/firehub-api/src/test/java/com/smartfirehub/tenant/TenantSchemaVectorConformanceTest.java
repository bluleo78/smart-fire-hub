package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link DataSchema#current()} 가 Python executor 의 {@code resolve_schema} 와 <b>같은 값</b>을
 * 내는지 고정벡터로 대조한다(P3-b2 T3).
 *
 * <p><b>이 표는 {@code apps/firehub-executor/tests/test_tenant_schema_vectors.py} 와 짝이다.
 * 한쪽만 고치지 말 것.</b> 스키마 파생 규약이 바뀌면 두 표를 함께 갱신하고, 둘 다 테스트를 다시
 * 통과시켜야 한다.
 *
 * <p><b>왜 Python 을 호출하지 않고 값을 복제하는가</b> — executor 는 별도 프로세스이고 Java
 * 코드를 부를 수 없다({@code app/tenant.py} 모듈 Javadoc 참조). 두 언어가 스키마명을 요청
 * 페이로드로 주고받지 않고 각자 독립적으로 파생하는 이유는 그쪽이 이미 상세히 남겨 뒀다 —
 * 요약하면 클라이언트(호출자) 제공 식별자를 신뢰하게 되는 보안 후퇴이기 때문이다. 드리프트
 * 방어는 이 고정벡터 표를 양 언어에 같은 값으로 두고 대조하는 것뿐이다.
 *
 * <p>스프링 컨텍스트를 띄우지 않는 순수 단위 테스트다({@code DataSchemaResolutionTest} 와 같은
 * 형태) — 검사 대상이 {@link DataSchema}의 순수 함수라 DB 도 빈도 필요 없다.
 */
class TenantSchemaVectorConformanceTest {

  /** 순수 단위 테스트라도 ThreadLocal 은 포크를 공유한다 — 뒤따르는 테스트로 새지 않게 지운다. */
  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  /**
   * Python {@code SCHEMA_VECTORS}(tests/test_tenant_schema_vectors.py)와 완전히 같은 값이다:
   * {@code (1, "data"), (2, "data_t2"), (7, "data_t7"), (43259, "data_t43259")}.
   */
  @ParameterizedTest
  @CsvSource({
    "1, data",
    "2, data_t2",
    "7, data_t7",
    "43259, data_t43259",
  })
  @DisplayName("DataSchema.current() 가 고정벡터와 일치한다 — Python resolve_schema 와 짝")
  void currentMatchesFixedVector(long tenantId, String expectedSchema) {
    assertThat(TenantContext.runScopedGet(tenantId, DataSchema::current)).isEqualTo(expectedSchema);
  }
}
