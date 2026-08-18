package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.MissingTenantScopeException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * {@link DataSchema} 의 스키마명 파생 규약을 고정한다.
 *
 * <p>이 규약은 설정이 아니라 코드 상수로 박혀 있다 — 프로필마다 값이 갈라지면 prod 가 실제로
 * 쓰는 매핑(테넌트 1 → data)이 테스트에서 한 번도 실행되지 않기 때문이다.
 *
 * <p>{@code runAsTenant} 라는 이름의 헬퍼는 이 저장소에 없다 — {@link TenantContext#runScopedGet}
 * 가 정확히 같은 의미론(테넌트 설정 → 실행 → 진입 전 값 복원)을 이미 제공하므로 그대로 쓴다.
 */
class DataSchemaTenantResolutionTest extends IntegrationTestBase {

  /** 기본 테넌트는 기존 스키마를 그대로 쓴다 — 리네임 없음이 이 밴드의 전제다. */
  @Test
  void defaultTenantKeepsLegacySchema() {
    assertThat(TenantContext.runScopedGet(1L, DataSchema::current)).isEqualTo("data");
  }

  /** 신규 테넌트는 data_t{id}. */
  @Test
  void newTenantsGetSuffixedSchema() {
    assertThat(TenantContext.runScopedGet(2L, DataSchema::current)).isEqualTo("data_t2");
    assertThat(TenantContext.runScopedGet(43259L, DataSchema::current)).isEqualTo("data_t43259");
  }

  /** qualify() 는 스키마가 바뀌어도 식별자만 인용한다. */
  @Test
  void qualifyUsesResolvedSchema() {
    assertThat(TenantContext.runScopedGet(2L, () -> DataSchema.qualify("my_table")))
        .isEqualTo("data_t2.\"my_table\"");
    assertThat(TenantContext.runScopedGet(1L, () -> DataSchema.qualify("my_table")))
        .isEqualTo("data.\"my_table\"");
  }

  /** 컨텍스트 없으면 조용히 기본 스키마로 떨어지지 않는다 — 그 폴백이 곧 크로스 테넌트 접근이다. */
  @Test
  void failsClosedWithoutTenantContext() {
    TenantContext.clear();
    assertThatThrownBy(DataSchema::current).isInstanceOf(MissingTenantScopeException.class);
  }

  /** 파생된 이름은 PostgreSQL 인용 없는 식별자로 안전해야 한다(소문자·숫자·밑줄). */
  @Test
  void derivedNamesAreSafeUnquotedIdentifiers() {
    for (long id : new long[] {1L, 2L, 7L, 43259L}) {
      assertThat(TenantContext.runScopedGet(id, DataSchema::current)).matches("[a-z_][a-z0-9_]*");
    }
  }
}
