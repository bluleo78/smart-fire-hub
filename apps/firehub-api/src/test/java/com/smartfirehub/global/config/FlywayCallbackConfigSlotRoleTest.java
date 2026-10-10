package com.smartfirehub.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.support.IntegrationTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * AFTER_MIGRATE 비밀번호 동기화 대상에 PYTHON 읽기 슬롯 롤이 들어 있어야 한다(WD-29) — 빠지면 V137 이 임의 비밀번호로 만든 롤이 그대로 남아 배포
 * 직후 모든 PYTHON 스텝이 인증 실패(fail-closed)로 멈춘다.
 *
 * <p>정적 메서드를 직접 부른다 — 로그인 테스트는 컨테이너에 이전 실행이 남긴 비밀번호가 있으면 콜백이 빠져도 통과할 수 있어, 이 계약의 증거로는 이 테스트를 쓴다.
 */
class FlywayCallbackConfigSlotRoleTest extends IntegrationTestBase {

  private static final String SECRET = "test-tenant-pipeline-secret";

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource owner;

  @Test
  void passwordSyncTargetsIncludeAllSlotRolesOfActiveTenant() throws SQLException {
    try (Connection c = owner.getConnection()) {
      Map<String, String> m =
          FlywayCallbackConfig.resolveActiveTenantPipelineRolePasswords(c, SECRET);
      for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
        assertThat(m)
            .containsEntry(
                TenantPipelineRole.pythonReadRoleName(1, k),
                TenantPipelineRole.pythonReadPassword(1, k, SECRET));
      }
      // 기존 실행 롤 동기화가 슬롯 루프 추가로 깨지지 않았는지
      assertThat(m).containsEntry("pipeline_executor_t1", TenantPipelineRole.password(1, SECRET));
    }
  }
}
