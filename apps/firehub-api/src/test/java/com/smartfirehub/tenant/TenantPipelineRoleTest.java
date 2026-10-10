package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantPipelineRole} 의 롤 이름·비밀번호 조립 규약을 고정하는 단위 테스트.
 *
 * <p>이 규약은 P3-b2(물리 스키마 리네임)가 그 위에서 스키마별 격리를 켜는 토대다 — 롤 이름 조립이 이 클래스 한 곳에서만 일어난다는 것을 이 테스트가 계속 지켜야,
 * 다른 호출부가 문자열을 직접 이어 붙이는 우회가 생기지 않는다.
 */
class TenantPipelineRoleTest {

  // 롤 이름 조립이 한 곳뿐임을 고정한다 — P3-b2 가 이 규약 위에서 스키마를 가른다.
  @Test
  void roleNameFollowsTenantSuffixConvention() {
    assertThat(TenantPipelineRole.roleName(1L)).isEqualTo("pipeline_executor_t1");
    assertThat(TenantPipelineRole.roleName(42L)).isEqualTo("pipeline_executor_t42");
  }

  /**
   * 파생 결과를 <b>고정값</b>으로 못박는다 — Python 쪽과의 교차 언어 계약을 이 언어에서 지키는 장치.
   *
   * <p><b>왜 결정성·형식 단언만으로는 부족한가.</b> Java 와 Python 이 각자 독립적으로 같은 비밀번호를 파생하고, 어긋나면 executor 가 DB 인증에
   * 실패한다. 그런데 결정성("두 번 부르면 같다")과 형식("32자 소문자 hex")만 단언하면 <b>양쪽이 서로 다른 값으로 각자 일관되게</b> 바뀌어도 두 스위트가
   * 모두 초록으로 남는다. 실제로 이 위험을 한 번 통과했다: hex 인코딩을 손수 만든 루프에서 {@code HexFormat} 으로 바꾼 커밋은 값이 바뀔 수 있는
   * 변경이었는데, 그때 이 파일에는 고정값 단언이 없어 라이브 DB 접속으로만 확인할 수 있었다(= CI 가 잡아 주지 않는다).
   *
   * <p>기대값은 손으로 계산한 것이 아니라 <b>실제로 도는 구현</b>에서 얻은 것이고, Python 쪽 {@code tests/test_tenant.py} 의
   * {@code JAVA_DERIVED_PASSWORDS} 와 <b>같은 세 쌍</b>이다. 어느 한쪽이 표류하면 그 언어의 스위트가 즉시 빨개진다 — 라이브 DB 없이도.
   *
   * <p>secret 값은 {@code application-test.yml} 의 {@code app.pipeline.role-password-secret} 과 같다.
   */
  @Test
  void passwordMatchesCrossLanguageKnownAnswerVectors() {
    String secret = "test-tenant-pipeline-secret";
    assertThat(TenantPipelineRole.password(1L, secret))
        .isEqualTo("ed3fe7de81f2c2ee70179ca788100837");
    assertThat(TenantPipelineRole.password(2L, secret))
        .isEqualTo("10c75c4b795e800a0381177d7352f8d4");
    assertThat(TenantPipelineRole.password(42L, secret))
        .isEqualTo("3f452492b5aaa69522dbcd1bbbce0db1");
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

  /**
   * 슬롯 롤 비밀번호 교차 언어 벡터 — Python app/tenant.py 의 resolve_read_password 와 바이트 단위로 같아야 한다. 값은
   * hmac.new(b"test-tenant-pipeline-secret", b"<롤 이름>", sha256).hexdigest()[:32] 로 계산했다(executor
   * test_tenant.py 와 같은 상수).
   */
  @Test
  void pythonReadPasswordMatchesCrossLanguageVectors() {
    String secret = "test-tenant-pipeline-secret";
    assertThat(TenantPipelineRole.pythonReadPassword(1, 1, secret))
        .isEqualTo("1dd5681ab158149c2569b01c8a93a0ed");
    assertThat(TenantPipelineRole.pythonReadPassword(2, 3, secret))
        .isEqualTo("73a7e61046bfdb868a142bd1b5e3e196");
    assertThat(TenantPipelineRole.pythonReadPassword(42, 10, secret))
        .isEqualTo("a5a0aa64723b5c3e07a047b698eb9197");
  }

  // 슬롯 롤 이름 규약 — pipeline_py_t{tenantId}_s{slot}.
  @Test
  void pythonReadRoleNameConvention() {
    assertThat(TenantPipelineRole.pythonReadRoleName(1, 1)).isEqualTo("pipeline_py_t1_s1");
    assertThat(TenantPipelineRole.pythonReadRoleName(42, 10)).isEqualTo("pipeline_py_t42_s10");
  }

  /** 슬롯 범위 밖·0 이하 테넌트는 조립하지 않는다 — 없는 롤 이름을 만들어 "롤 없음"으로 늦게 터지는 것보다 즉시 실패가 낫다. */
  @Test
  void pythonReadRoleRejectsOutOfRange() {
    assertThatThrownBy(() -> TenantPipelineRole.pythonReadRoleName(1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TenantPipelineRole.pythonReadRoleName(1, 11))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TenantPipelineRole.pythonReadRoleName(0, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 슬롯 롤 비밀번호는 같은 테넌트의 실행 롤 비밀번호와 달라야 한다(한쪽 유출이 다른 쪽 접속이 되지 않게). */
  @Test
  void pythonReadPasswordDiffersFromExecutorPassword() {
    String secret = "test-tenant-pipeline-secret";
    assertThat(TenantPipelineRole.pythonReadPassword(1, 1, secret))
        .isNotEqualTo(TenantPipelineRole.password(1, secret));
    assertThatThrownBy(() -> TenantPipelineRole.pythonReadPassword(1, 1, " "))
        .isInstanceOf(IllegalStateException.class);
  }
}
