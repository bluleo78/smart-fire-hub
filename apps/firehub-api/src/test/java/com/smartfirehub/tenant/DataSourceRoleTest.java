package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;

import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 런타임 트래픽이 비특권 롤로 접속하는지 단언한다.
 *
 * <p>이 테스트가 회귀 방지의 핵심이다 — 런타임이 소유자/SUPERUSER 롤로 되돌아가면 모든 RLS 정책이
 * 조용히 무력화되고, 다른 어떤 테스트도 그 사실을 알려주지 않는다.
 */
class DataSourceRoleTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("런타임 접속 롤은 app_tenant 다")
  void runtimeConnectsAsAppTenant() {
    String currentUser = dsl.select(field("current_user", String.class)).fetchOne(0, String.class);

    assertThat(currentUser).isEqualTo("app_tenant");
  }

  @Test
  @DisplayName("런타임 롤은 SUPERUSER 도 BYPASSRLS 도 아니다")
  void runtimeRoleCannotBypassRls() {
    // rolname 을 리터럴로 고정하면 "app_tenant 라는 이름의 롤"의 속성만 확인하게 되어, 실제 접속 신원과
    // 분리된다. current_user 로 비교해 이 단언이 "지금 접속한 롤"을 직접 검사하도록 한다 —
    // 런타임이 특권 롤로 되돌아가는 회귀를 잡는 유일한 장치이므로 자기완결적이어야 한다.
    var record =
        dsl.select(field("rolsuper", Boolean.class), field("rolbypassrls", Boolean.class))
            .from("pg_roles")
            .where(field("rolname", String.class).eq(field("current_user", String.class)))
            .fetchOne();

    assertThat(record).as("current_user 에 해당하는 pg_roles 행이 있어야 한다").isNotNull();
    assertThat(record.get(0, Boolean.class)).isFalse();
    assertThat(record.get(1, Boolean.class)).isFalse();
  }
}
