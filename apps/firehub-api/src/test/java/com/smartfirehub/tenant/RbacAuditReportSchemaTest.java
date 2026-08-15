package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V97 이 만든 스키마 형태를 카탈로그로 고정한다.
 *
 * <p>왜 필요한가: 유니크 인덱스는 RLS 와 무관하게 전역으로 적용되므로, role 이름 유니크를 접지
 * 않으면 두 번째 테넌트가 'USER' 역할을 만들 때 충돌한다. 이 회귀는 테넌트가 하나뿐인 동안
 * 행위 테스트로는 드러나지 않아서 카탈로그로 못박는다.
 */
class RbacAuditReportSchemaTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  private boolean columnIsNullable(String table, String column) {
    return "YES"
        .equals(
            dsl.fetchValue(
                "select is_nullable from information_schema.columns"
                    + " where table_schema='public' and table_name=? and column_name=?",
                table,
                column));
  }

  @Test
  @DisplayName("5테이블에 tenant_id 가 있고 audit_log 만 nullable 이다")
  void tenantColumnsExistWithAuditLogNullable() {
    assertThat(columnIsNullable("role", "tenant_id")).isFalse();
    assertThat(columnIsNullable("role_permission", "tenant_id")).isFalse();
    assertThat(columnIsNullable("user_role", "tenant_id")).isFalse();
    assertThat(columnIsNullable("report_template", "tenant_id")).isFalse();
    // 로그인·회원가입 감사는 테넌트 선택 전에 기록되므로 NULL 이어야 한다.
    assertThat(columnIsNullable("audit_log", "tenant_id")).isTrue();
  }

  @Test
  @DisplayName("role 이름 유니크가 (tenant_id, name) 으로 접혀 있다")
  void roleNameUniqueIsTenantScoped() {
    Integer folded =
        (Integer)
            dsl.fetchValue(
                "select count(*)::int from pg_indexes"
                    + " where schemaname='public' and indexname='role_tenant_name_key'");
    assertThat(folded).isEqualTo(1);

    // 전역 유니크가 남아 있으면 두 번째 테넌트의 'USER' 생성이 충돌한다.
    Integer global =
        (Integer)
            dsl.fetchValue(
                "select count(*)::int from pg_constraint where conname='role_name_key'");
    assertThat(global).isZero();
  }

  @Test
  @DisplayName("정리 잡용 테넌트 선두 복합 인덱스가 존재한다 (이슈 #384-1)")
  void perTenantCleanupIndexesExist() {
    Integer count =
        (Integer)
            dsl.fetchValue(
                "select count(*)::int from pg_indexes where schemaname='public' and indexname in"
                    + " ('idx_async_job_tenant_updated','idx_async_job_tenant_created',"
                    + " 'idx_trigger_event_tenant_created','idx_api_connection_tenant_healthcheck')");
    assertThat(count).isEqualTo(4);
  }
}
