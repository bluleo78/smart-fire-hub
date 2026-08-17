package com.smartfirehub.global.config;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway migrate 실행에 커스텀 콜백(pipeline_executor, app_tenant, 테넌트별 파이프라인 실행 롤
 * 비밀번호 동기화)을 등록한다.
 *
 * <p>{@code FlywayConfigurationCustomizer} 빈을 두 개 등록하면 안 된다 — Flyway 설정 커스터마이저는
 * 순서가 보장되지 않고, {@code configuration.callbacks(...)} 는 "추가"가 아니라 "교체"이므로
 * 나중에 실행되는 커스터마이저가 먼저 등록한 콜백을 통째로 지워버린다. 그래서 콜백을 반드시
 * 하나의 커스터마이저 안에서 같은 {@code callbacks(...)} 호출로 함께 등록한다.
 */
@Configuration
public class FlywayCallbackConfig {

  @Bean
  public FlywayConfigurationCustomizer passwordSyncCustomizer(
      @Value("${app.pipeline.datasource.password}") String pipelineExecutorPassword,
      @Value("${spring.datasource.password}") String appTenantPassword,
      @Value("${app.pipeline.role-password-secret}") String tenantPipelineRoleSecret) {
    return configuration ->
        configuration.callbacks(
            new RolePasswordSyncCallback("pipeline_executor", pipelineExecutorPassword),
            new RolePasswordSyncCallback("app_tenant", appTenantPassword),
            new RolePasswordSyncCallback(
                "tenantPipelineExecutorPasswordSync",
                connection -> resolveActiveTenantPipelineRolePasswords(connection, tenantPipelineRoleSecret)));
  }

  /**
   * ACTIVE 테넌트마다 {@code pipeline_executor_t{id}} 롤의 비밀번호를 {@link TenantPipelineRole}
   * 파생값으로 맞춘다. 롤 이름은 반드시 {@link TenantPipelineRole#roleName} 을 거쳐 조립한다(문자열
   * 직접 조립 금지 — TenantPipelineRoleTest 가 규약을 고정한다).
   *
   * <p>V111 은 테넌트 1 의 롤만 만든다(R6) — 새 테넌트를 위한 롤 생성은 운영자가 실행하는 문서화된
   * 절차(#383)이지 이 애플리케이션이 자동으로 만드는 것이 아니다. 그래서 ACTIVE 테넌트라도 아직 롤이
   * 없으면 조용히 건너뛴다: 여기서 실패하거나 앱 기동을 막으면, 아직 프로비저닝되지 않은 테넌트
   * 하나 때문에 이미 정상 동작 중인 다른 모든 테넌트까지 기동이 막히는 과잉 대응이 된다.
   */
  private static Map<String, String> resolveActiveTenantPipelineRolePasswords(
      Connection connection, String secret) throws SQLException {
    Map<String, String> rolePasswords = new LinkedHashMap<>();
    try (Statement stmt = connection.createStatement();
        ResultSet activeTenants =
            stmt.executeQuery("SELECT id FROM tenant WHERE status = 'ACTIVE' ORDER BY id")) {
      while (activeTenants.next()) {
        long tenantId = activeTenants.getLong("id");
        String roleName = TenantPipelineRole.roleName(tenantId);
        if (roleExists(connection, roleName)) {
          rolePasswords.put(roleName, TenantPipelineRole.password(tenantId, secret));
        }
      }
    }
    return rolePasswords;
  }

  private static boolean roleExists(Connection connection, String roleName) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
      ps.setString(1, roleName);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }
}
