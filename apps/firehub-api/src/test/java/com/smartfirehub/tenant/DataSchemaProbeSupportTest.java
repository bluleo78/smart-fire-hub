package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * {@link DataSchemaProbeSupport} 자기검증 — 프로브 헬퍼 자체가 계약을 지키는지 본다.
 *
 * <p><b>왜 이 헬퍼가 필요한가.</b> test DB 의 {@code data} 스키마는 객체가 0개다. P1 Task 7 의 스키마
 * 소유권 가드는 바로 이 빈 스키마를 상대로 2108건이 통과하면서 실제로는 아무것도 검사하지 않았다.
 * 게다가 그 가드는 프로브를 <b>런타임 롤로</b> 만들었기 때문에 프로브가 언제나 {@code app_tenant}
 * 소유가 되어, "소유자가 {@code app} 인 테이블" 이라는 검증 대상 조건을 재현할 수조차 없었다. 이
 * 헬퍼는 그 두 함정(빈 스키마 / 소유자 고정)을 한 곳에서 막기 위한 것이다.
 *
 * <p><b>왜 소유권 테스트가 "대비" 형태인가.</b> 컨텍스트 하나로 만들어 소유자를 단언하면 PostgreSQL
 * 의 동작을 확인할 뿐 헬퍼를 검증하지 못한다(= P1 Task 7 과 같은 공허함). 소유자 자격증명(app)과
 * 런타임 자격증명(app_tenant)으로 각각 만들어 <b>서로 다른</b> 소유자가 나오는지 봐야, 헬퍼가
 * 소유권을 런타임 롤로 뭉개지 않는다는 것이 증명된다.
 *
 * <p><b>이름은 실행마다 고유해야 한다.</b> {@code smartfirehub_test} 는 다른 세션과 공유하므로 고정
 * 이름을 쓰면 동시 실행이 서로의 테이블을 만들거나 지운다(P2-f 에서 남의 행 3946건을 지운 전례).
 */
class DataSchemaProbeSupportTest extends IntegrationTestBase {

  private static final String DATA_SCHEMA = "data";

  /** 런타임 롤(app_tenant) 커넥션. */
  @Autowired private DSLContext dsl;

  @Value("${spring.flyway.url}")
  private String ownerUrl;

  @Value("${spring.flyway.user}")
  private String ownerUser;

  @Value("${spring.flyway.password}")
  private String ownerPassword;

  // 이 테스트 인스턴스가 만든 이름만 담는다 — 정리 대상은 자기가 만든 것뿐이어야 한다.
  private final String ownerProbe = DataSchemaProbeSupport.uniqueProbeName("owner");
  private final String runtimeProbe = DataSchemaProbeSupport.uniqueProbeName("runtime");
  private final String decoy = "p3decoy_" + TenantRlsTestSupport.nextTenantId();

  @AfterEach
  void dropWhatThisTestCreated() throws SQLException {
    // 소유자 자격증명으로 지운다. app 소유 프로브는 app_tenant 로는 DROP 이 안 되기 때문이다.
    withOwner(
        owner -> {
          owner.execute("drop table if exists " + DATA_SCHEMA + "." + ownerProbe);
          owner.execute("drop table if exists " + DATA_SCHEMA + "." + runtimeProbe);
          owner.execute("drop table if exists " + DATA_SCHEMA + "." + decoy);
        });
  }

  @Test
  @DisplayName("프로브 테이블은 생성에 사용한 롤의 소유가 된다(app vs app_tenant 대비)")
  void createProbeTable_isOwnedByTheRequestedRole() throws SQLException {
    withOwner(owner -> DataSchemaProbeSupport.createProbeTable(owner, DATA_SCHEMA, ownerProbe));
    DataSchemaProbeSupport.createProbeTable(dsl, DATA_SCHEMA, runtimeProbe);

    assertThat(DataSchemaProbeSupport.tableOwner(dsl, DATA_SCHEMA, ownerProbe))
        .as("소유자 자격증명으로 만든 프로브는 app 소유여야 V86 이전 상태를 재현할 수 있다")
        .isEqualTo("app");
    assertThat(DataSchemaProbeSupport.tableOwner(dsl, DATA_SCHEMA, runtimeProbe))
        .as("런타임 롤로 만든 프로브는 app_tenant 소유여야 한다")
        .isEqualTo("app_tenant");
  }

  @Test
  @DisplayName("접두어 없는 이름으로는 프로브를 만들 수 없다")
  void createProbeTable_rejectsNameWithoutProbePrefix() {
    assertThatThrownBy(() -> DataSchemaProbeSupport.createProbeTable(dsl, DATA_SCHEMA, "victim"))
        .as("공유 test DB 에서 남의 테이블을 만들거나 지우지 못하게 본문에서 막는다")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("접두어 없는 이름은 drop 도 거부하고, 대상 테이블은 그대로 남는다")
  void dropProbeTable_refusesToDropWhatItDidNotCreate() throws SQLException {
    // 프로브가 아닌 테이블을 하나 만들어 둔다(우리가 만든 것이라 정리도 우리가 한다).
    dsl.execute("create table " + DATA_SCHEMA + "." + decoy + " (id bigint primary key)");

    assertThatThrownBy(() -> DataSchemaProbeSupport.dropProbeTable(dsl, DATA_SCHEMA, decoy))
        .as("이름 규약이 아니라 본문 가드로 막아야 한다")
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(DataSchemaProbeSupport.tableOwner(dsl, DATA_SCHEMA, decoy))
        .as("거부됐으므로 테이블은 그대로 살아 있어야 한다")
        .isNotNull();
  }

  @Test
  @DisplayName("drop 은 자기가 만든 프로브를 실제로 지운다")
  void dropProbeTable_removesTheProbeItCreated() {
    DataSchemaProbeSupport.createProbeTable(dsl, DATA_SCHEMA, runtimeProbe);
    assertThat(DataSchemaProbeSupport.tableOwner(dsl, DATA_SCHEMA, runtimeProbe)).isNotNull();

    DataSchemaProbeSupport.dropProbeTable(dsl, DATA_SCHEMA, runtimeProbe);

    assertThat(DataSchemaProbeSupport.tableOwner(dsl, DATA_SCHEMA, runtimeProbe))
        .as("존재하지 않는 테이블의 소유자는 null 이어야 한다")
        .isNull();
  }

  @Test
  @DisplayName("uniqueProbeName 은 접두어를 붙이고 호출마다 다른 이름을 만든다")
  void uniqueProbeName_isPrefixedAndUnique() {
    String a = DataSchemaProbeSupport.uniqueProbeName("x");
    String b = DataSchemaProbeSupport.uniqueProbeName("x");

    assertThat(a).startsWith(DataSchemaProbeSupport.PROBE_PREFIX);
    assertThat(a).as("공유 test DB 에서 동시 실행끼리 충돌하지 않으려면 매번 달라야 한다").isNotEqualTo(b);
  }

  /** 소유자(app) 자격증명으로 짧게 커넥션을 열어 작업한다 — 슈퍼유저 커넥션을 붙잡아 두지 않는다. */
  private void withOwner(OwnerAction action) throws SQLException {
    try (Connection conn = DriverManager.getConnection(ownerUrl, ownerUser, ownerPassword);
        Statement keepAlive = conn.createStatement()) {
      // Statement 는 커넥션이 실제로 열렸는지 즉시 드러내기 위한 것이다(조용한 지연 실패 방지).
      keepAlive.execute("select 1");
      action.run(DSL.using(conn));
    }
  }

  @FunctionalInterface
  private interface OwnerAction {
    void run(DSLContext owner);
  }
}
