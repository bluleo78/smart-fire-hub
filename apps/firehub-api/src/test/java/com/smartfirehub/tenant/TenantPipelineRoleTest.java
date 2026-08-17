package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantPipelineRole} 의 롤 이름·비밀번호 조립 규약을 고정하는 단위 테스트.
 *
 * <p>이 규약은 P3-b2(물리 스키마 리네임)가 그 위에서 스키마별 격리를 켜는 토대다 — 롤 이름 조립이
 * 이 클래스 한 곳에서만 일어난다는 것을 이 테스트가 계속 지켜야, 다른 호출부가 문자열을 직접 이어
 * 붙이는 우회가 생기지 않는다.
 */
class TenantPipelineRoleTest {

  // 롤 이름 조립이 한 곳뿐임을 고정한다 — P3-b2 가 이 규약 위에서 스키마를 가른다.
  @Test
  void roleNameFollowsTenantSuffixConvention() {
    assertThat(TenantPipelineRole.roleName(1L)).isEqualTo("pipeline_executor_t1");
    assertThat(TenantPipelineRole.roleName(42L)).isEqualTo("pipeline_executor_t42");
  }

  // 비번은 결정적이어야 한다 — 저장하지 않고 매번 파생하기 때문(설계서 §5).
  @Test
  void passwordIsDeterministicAndTenantScoped() {
    String a1 = TenantPipelineRole.password(1L, "s3cret");
    assertThat(TenantPipelineRole.password(1L, "s3cret")).isEqualTo(a1);
    assertThat(TenantPipelineRole.password(2L, "s3cret")).isNotEqualTo(a1);
    assertThat(TenantPipelineRole.password(1L, "other")).isNotEqualTo(a1);
    assertThat(a1).hasSize(32).matches("[0-9a-f]{32}");
  }

  // 음수·0 테넌트 id 는 롤 이름을 오염시킨다(하이픈은 인용 없이는 불법 식별자).
  @Test
  void rejectsNonPositiveTenantId() {
    assertThatThrownBy(() -> TenantPipelineRole.roleName(0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TenantPipelineRole.roleName(-1L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
