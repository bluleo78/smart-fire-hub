package com.smartfirehub.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.support.IntegrationTestBase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

  /**
   * 롤 존재 확인은 pg_roles 한 번(CR9) — 예전엔 테넌트당 11번 왕복했다. 롤이 하나도 없는 ACTIVE 테넌트는 조용히 건너뛴다(같은 커넥션의 롤백될 트랜잭션
   * 안에서 테넌트 행만 넣어 본다).
   */
  @Test
  void resolvesRoleExistenceWithOneQuery_andSkipsTenantWithoutRoles() throws SQLException {
    try (Connection c = owner.getConnection()) {
      c.setAutoCommit(false);
      try {
        long bare;
        try (var st = c.createStatement();
            var rs =
                st.executeQuery(
                    "INSERT INTO tenant (slug, name, status) VALUES ('cr9-bare', 'cr9', 'ACTIVE')"
                        + " RETURNING id")) {
          rs.next();
          bare = rs.getLong(1);
        }
        AtomicInteger prepared = new AtomicInteger();
        Connection counting =
            (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                      if (method.getName().equals("prepareStatement")) {
                        prepared.incrementAndGet();
                      }
                      try {
                        return method.invoke(c, args);
                      } catch (InvocationTargetException e) {
                        throw e.getCause();
                      }
                    });

        Map<String, String> m =
            FlywayCallbackConfig.resolveActiveTenantPipelineRolePasswords(counting, SECRET);

        assertThat(prepared).as("pg_roles 조회는 한 번").hasValue(1);
        assertThat(m).containsKey("pipeline_executor_t1");
        assertThat(m)
            .doesNotContainKeys(
                TenantPipelineRole.roleName(bare), TenantPipelineRole.pythonReadRoleName(bare, 1));
      } finally {
        c.rollback();
        c.setAutoCommit(true);
      }
    }
  }
}
