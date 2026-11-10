package com.smartfirehub.global.platformdb.tibero.flyway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Tibero 테넌트 격리 마이그레이션 배선(WD-492)의 단위 검사 — 컨텍스트 이름 계산, 단일 계정 판정, 이름 검증, Flyway 자리표시자·콜백 등록.
 *
 * <p>실서버 없이 돈다. 실제 권한 부여·컨텍스트 동작은 {@link TiberoTenantRlsLiveTest} 가 본다.
 */
class TiberoTenantMigrationTest {

  @Test
  void 컨텍스트_이름은_스키마_해시로_정해진다() {
    String name = TiberoTenantMigration.contextNameFor("FH_OWNER");

    assertThat(name).matches("^FH_TENANT_[0-9A-F]{8}$");
    // 대소문자는 Tibero 가 따옴표 없는 이름을 대문자로 접으므로 같은 스키마다.
    assertThat(TiberoTenantMigration.contextNameFor("fh_owner")).isEqualTo(name);
    assertThat(TiberoTenantMigration.contextNameFor("FH_OTHER")).isNotEqualTo(name);
  }

  @Test
  void 같은_이름이면_단일_계정이다() {
    assertThat(TiberoTenantMigration.isSingleAccount("FH_OWNER", "fh_owner")).isTrue();
    assertThat(TiberoTenantMigration.isSingleAccount("FH_OWNER", null)).isTrue();
    assertThat(TiberoTenantMigration.isSingleAccount("FH_OWNER", "FH_APP")).isFalse();
  }

  @Test
  void 잘못된_이름은_거부한다() {
    FluentConfiguration c = Flyway.configure();

    assertThatThrownBy(() -> TiberoTenantMigration.configure(c, "A;DROP", "X"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TiberoTenantMigration.configure(c, "OWNER", "B C"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 자리표시자를_넣는다() {
    FluentConfiguration c =
        TiberoTenantMigration.configure(Flyway.configure(), "fh_owner", "fh_app");

    Map<String, String> placeholders = c.getPlaceholders();
    assertThat(placeholders)
        .containsEntry("tenantContext", TiberoTenantMigration.contextNameFor("FH_OWNER"))
        .containsEntry("ownerSchema", "FH_OWNER");
    assertThat(c.getCallbacks()).hasSize(1);
    assertThat(c.getCallbacks()[0]).isInstanceOf(TiberoRuntimeGrantCallback.class);
  }

  @Test
  void 단일_계정이면_자리표시자만_넣고_콜백은_달지_않는다() {
    // 콜백이 있으면 Flyway 가 이벤트 연결을 하나 더 연다 — 할 일 없는 단일 계정 모드에서는 달지 않는다.
    FluentConfiguration c = TiberoTenantMigration.configure(Flyway.configure(), "fh_owner", null);

    assertThat(c.getPlaceholders()).containsEntry("ownerSchema", "FH_OWNER");
    assertThat(c.getCallbacks()).isEmpty();
  }

  @Test
  void 앞서_넣은_자리표시자와_콜백은_지우지_않는다() {
    // Spring Boot 는 커스터마이저보다 먼저 설정의 자리표시자·콜백 빈을 넣는다 — 교체하면 그것들이 사라진다.
    FluentConfiguration base =
        Flyway.configure()
            .placeholders(Map.of("other", "v"))
            .callbacks(new TiberoRuntimeGrantCallback("X", null));

    FluentConfiguration c = TiberoTenantMigration.configure(base, "fh_owner", "fh_app");

    assertThat(c.getPlaceholders()).containsEntry("other", "v").containsKey("tenantContext");
    assertThat(c.getCallbacks()).hasSize(2);
  }
}
