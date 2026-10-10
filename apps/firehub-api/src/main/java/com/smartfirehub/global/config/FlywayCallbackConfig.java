package com.smartfirehub.global.config;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway migrate 실행에 커스텀 콜백(pipeline_executor, app_tenant, 테넌트별 파이프라인 실행 롤 비밀번호 동기화)을 등록한다.
 *
 * <p>{@code FlywayConfigurationCustomizer} 빈을 두 개 등록하면 안 된다 — Flyway 설정 커스터마이저는 순서가 보장되지 않고, {@code
 * configuration.callbacks(...)} 는 "추가"가 아니라 "교체"이므로 나중에 실행되는 커스터마이저가 먼저 등록한 콜백을 통째로 지워버린다. 그래서 콜백을
 * 반드시 하나의 커스터마이저 안에서 같은 {@code callbacks(...)} 호출로 함께 등록한다.
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
                connection ->
                    resolveActiveTenantPipelineRolePasswords(
                        connection, tenantPipelineRoleSecret)));
  }

  /**
   * ACTIVE 테넌트마다 {@code pipeline_executor_t{id}} 롤과 PYTHON 읽기 슬롯 롤({@code pipeline_py_t{id}_s{k}})의
   * 비밀번호를 {@link TenantPipelineRole} 파생값으로 맞춘다(테스트가 직접 부르도록 package-private). 롤 이름은 반드시 {@link
   * TenantPipelineRole#roleName} 을 거쳐 조립한다(문자열 직접 조립 금지 — TenantPipelineRoleTest 가 규약을 고정한다).
   *
   * <p>롤 생성은 이 콜백의 일이 아니다 — {@code TenantPipelineRoleProvisioner} 가 테넌트 생성 시점에 만들고, {@code
   * TenantPipelineRoleBootstrap} 이 기동 시 기존 테넌트를 훑는다(#680). 여기에 넣지 않은 이유: Flyway 콜백은 원시 {@code
   * Connection} 만 받아 스프링 빈 ({@code TenantSchemaProvisioner})에 닿지 못하므로, 롤만 만들고 스키마 GRANT 는 못 거는 절반짜리
   * 치유가 된다. 이 콜백에 남은 일은 <b>비밀번호 동기화</b>뿐이고 그건 커넥션 하나로 충분하다(시크릿 회전 시에도 이 경로가 정본이다).
   *
   * <p>그래서 ACTIVE 테넌트라도 아직 롤이 없으면 조용히 건너뛴다: 여기서 실패하거나 앱 기동을 막으면, 아직 프로비저닝되지 않은 테넌트 하나 때문에 이미 정상 동작
   * 중인 다른 모든 테넌트까지 기동이 막히는 과잉 대응이 된다. 콜백은 마이그레이션 단계라 기동 치유({@code ApplicationReadyEvent})보다
   * <b>먼저</b> 돈다는 점도 같은 결론을 가리킨다 — 갓 만들어진 롤은 이미 파생 비밀번호를 갖고 태어나므로 이 콜백이 그것을 기다릴 이유가 없다.
   *
   * <p><b>비밀번호는 매 기동 다시 건다(CR9 판단).</b> 저장된 SCRAM 검증자로는 현재 secret 과 맞는지 알 수 없어, "필요할 때만" 걸려면 secret
   * 지문 표식을 따로 둬야 한다. 표식이 어긋나면(수동 ALTER·복원·표식 저장 실패) 회전 뒤 비밀번호가 안 맞아 그 테넌트 PYTHON·SQL 실행이 전부 인증 실패로
   * 멈춘다. 실측 비용은 ALTER ROLE PASSWORD 한 문장 약 2ms(서버 SCRAM 해시, 격리 DB 110회 220ms)라 테넌트당 11문장 약 22ms —
   * 기존 실행 롤 동기화와 같이 매 기동 멱등 재설정을 유지한다. 줄이는 것은 존재 확인 왕복뿐이다.
   */
  static Map<String, String> resolveActiveTenantPipelineRolePasswords(
      Connection connection, String secret) throws SQLException {
    // 후보(ACTIVE 테넌트의 실행 롤 + 슬롯 롤 10개)를 순서대로 모은 뒤 pg_roles 를 한 번만 조회해 있는 것만 남긴다(CR9) — 예전엔 롤마다
    // 존재 확인 왕복이 테넌트당 11번이었다.
    Map<String, String> candidates = new LinkedHashMap<>();
    try (Statement stmt = connection.createStatement();
        ResultSet activeTenants =
            stmt.executeQuery("SELECT id FROM tenant WHERE status = 'ACTIVE' ORDER BY id")) {
      while (activeTenants.next()) {
        long tenantId = activeTenants.getLong("id");
        candidates.put(
            TenantPipelineRole.roleName(tenantId), TenantPipelineRole.password(tenantId, secret));
        // PYTHON 읽기 슬롯 롤(WD-29). V137 이 임의 비밀번호로 만든 롤을 여기서 파생값으로 맞춘다 — 빠지면 배포 직후
        // 모든 PYTHON 스텝이 인증 실패로 멈춘다(FlywayCallbackConfigSlotRoleTest 가 고정).
        for (int slot = 1; slot <= TenantPipelineRole.PYTHON_READ_SLOTS; slot++) {
          candidates.put(
              TenantPipelineRole.pythonReadRoleName(tenantId, slot),
              TenantPipelineRole.pythonReadPassword(tenantId, slot, secret));
        }
      }
    }
    // 없는 롤은 위 Javadoc 의 이유로 조용히 건너뛴다.
    Set<String> existing = existingRoles(connection, candidates.keySet());
    candidates.keySet().retainAll(existing);
    return candidates;
  }

  /** 주어진 이름 중 pg_roles 에 있는 것 — 한 번의 조회로. */
  private static Set<String> existingRoles(Connection connection, Set<String> names)
      throws SQLException {
    Set<String> existing = new HashSet<>();
    if (names.isEmpty()) {
      return existing;
    }
    try (PreparedStatement ps =
        connection.prepareStatement("SELECT rolname FROM pg_roles WHERE rolname = ANY (?)")) {
      ps.setArray(1, connection.createArrayOf("text", names.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          existing.add(rs.getString(1));
        }
      }
    }
    return existing;
  }
}
