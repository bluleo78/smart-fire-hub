package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V114 가 만든 tenant_settings 스키마 형태를 카탈로그로 고정한다.
 *
 * <p>왜 필요한가: P7-b 설정 2단 상속의 저장소 계층이다. RLS 정책이 audit_log 의 IS NOT DISTINCT
 * FROM idiom 을 잘못 복사하면 NULL 테넌트 컨텍스트에서 전 테넌트 행이 노출될 수 있으므로, 정책
 * 표현식 문자열을 직접 검사해 이 회귀를 막는다. 또한 "app_tenant 에 명시 GRANT 없이도 V83 의
 * ALTER DEFAULT PRIVILEGES 로 권한이 자동 부여된다"는 가정을 has_table_privilege 로 실측한다.
 */
class TenantSettingsSchemaTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("tenant_settings 테이블이 존재하고 RLS 가 켜져 있다")
  void tableExistsWithRlsEnabled() {
    Boolean rowSecurity =
        (Boolean)
            dsl.fetchValue("select relrowsecurity from pg_class where relname = 'tenant_settings'");
    assertThat(rowSecurity).isTrue();
  }

  @Test
  @DisplayName("정책이 정확히 1개이고 이름이 tenant_settings_tenant_isolation 이다")
  void exactlyOnePolicyWithExpectedName() {
    Integer count =
        (Integer)
            dsl.fetchValue(
                "select count(*)::int from pg_policies where tablename = 'tenant_settings'");
    assertThat(count).isEqualTo(1);

    String policyName =
        (String)
            dsl.fetchValue(
                "select policyname from pg_policies where tablename = 'tenant_settings'");
    assertThat(policyName).isEqualTo("tenant_settings_tenant_isolation");
  }

  @Test
  @DisplayName("정책 표현식이 plain '=' 이고 audit_log 의 IS NOT DISTINCT FROM idiom 을 복사하지 않았다")
  void policyExpressionDoesNotCopyAuditLogIdiom() {
    // 정책 표현식 가드 — audit_log 의 NULL 허용 idiom 이 복사되지 않았는지 본다.
    // tenant_id 가 NOT NULL 인 이 테이블에서 IS NOT DISTINCT FROM 을 쓰면 NULL 컨텍스트에서
    // 오히려 전 테넌트 행을 노출하는 방향으로 안전 실패한다.
    String qual =
        (String)
            dsl.fetchValue("select qual from pg_policies where tablename = 'tenant_settings'");
    assertThat(qual).contains("app.tenant_id");
    assertThat(qual).doesNotContain("IS NOT DISTINCT FROM");

    // WITH CHECK 는 pg_policies 의 별도 컬럼(with_check)이다 — qual 만 읽으면 <b>쓰기 쪽 절반이
    // 전혀 검증되지 않는다</b>. V114 는 USING 과 WITH CHECK 를 각각 선언하는데, WITH CHECK 를
    // (true) 로 바꿔도 qual 단언은 그대로 통과한다. 그러면 테넌트 커넥션이 <b>남의 tenant_id 로
    // 행을 INSERT</b> 할 수 있고, 읽을 수는 없으니 아무도 눈치채지 못한다.
    String withCheck =
        (String)
            dsl.fetchValue("select with_check from pg_policies where tablename = 'tenant_settings'");
    assertThat(withCheck).isNotNull();
    assertThat(withCheck).contains("app.tenant_id");
    assertThat(withCheck).doesNotContain("IS NOT DISTINCT FROM");
  }

  @Test
  @DisplayName("PK 가 (tenant_id, key) 두 컬럼이다")
  void primaryKeyIsTenantIdAndKey() {
    var pkColumns =
        dsl.fetch(
                "select kcu.column_name"
                    + " from information_schema.table_constraints tc"
                    + " join information_schema.key_column_usage kcu"
                    + "   on tc.constraint_name = kcu.constraint_name"
                    + "  and tc.table_schema = kcu.table_schema"
                    + " where tc.table_schema = 'public'"
                    + "   and tc.table_name = 'tenant_settings'"
                    + "   and tc.constraint_type = 'PRIMARY KEY'"
                    + " order by kcu.ordinal_position")
            .getValues(0, String.class);
    assertThat(pkColumns).containsExactly("tenant_id", "key");
  }

  @Test
  @DisplayName("app_tenant 가 tenant_settings 에 SELECT/INSERT/UPDATE/DELETE 를 갖는다")
  void appTenantHasFullDml() {
    // "GRANT 를 명시하지 않아도 V83 의 ALTER DEFAULT PRIVILEGES 로 권한이 자동 부여된다"는
    // 가정의 실측. 실패하면 V114 에 명시 GRANT 가 필요하다는 뜻이다.
    for (String privilege : new String[] {"SELECT", "INSERT", "UPDATE", "DELETE"}) {
      Boolean hasPrivilege =
          (Boolean)
              dsl.fetchValue(
                  "select has_table_privilege('app_tenant', 'tenant_settings', ?)", privilege);
      assertThat(hasPrivilege).as("app_tenant %s on tenant_settings", privilege).isTrue();
    }
  }
}
