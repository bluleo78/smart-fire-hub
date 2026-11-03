package com.smartfirehub.global.platformdb.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.platformdb.PlatformTenantSession;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgresPlatformTenantSession 의 계약(WD-486) — 심은 테넌트가 같은 트랜잭션에서만 보이고 커밋 뒤에는 사라지는지(트랜잭션 로컬)를 실제 DB
 * 로 확인한다. 클래스 레벨 {@code @Transactional} 은 쓰지 않는다 — 커밋 경계가 필요하다.
 */
class PostgresPlatformTenantSessionTest extends IntegrationTestBase {

  @Autowired private PlatformTenantSession session;
  @Autowired private DataSource dataSource;
  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  /** 현재 커넥션의 GUC 값을 읽는다(미설정이면 null). */
  private String readGuc() {
    return dsl.fetchOne("select current_setting('app.tenant_id', true)").get(0, String.class);
  }

  @Test
  @DisplayName("applyToConnection 은 같은 트랜잭션에서 GUC 로 읽히고 커밋 뒤에는 비어 있다")
  void applyToConnectionIsTransactionLocal() {
    // 이후 트랜잭션이 스스로 GUC 를 심지 않도록 컨텍스트를 비운다.
    TenantContext.clear();

    String inside =
        tx.execute(
            s -> {
              try {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                session.applyToConnection(connection, 9L);
              } catch (SQLException e) {
                throw new IllegalStateException(e);
              }
              return readGuc();
            });
    String after = tx.execute(s -> readGuc());

    assertThat(inside).isEqualTo("9");
    assertThat(after).isNullOrEmpty();
  }

  @Test
  @DisplayName("applyToCurrentTransaction 은 현재 트랜잭션에 테넌트를 심는다")
  void applyToCurrentTransactionPlantsTenant() {
    TenantContext.clear();

    String inside =
        tx.execute(
            s -> {
              session.applyToCurrentTransaction(11L);
              return readGuc();
            });
    String after = tx.execute(s -> readGuc());

    assertThat(inside).isEqualTo("11");
    assertThat(after).isNullOrEmpty();
  }
}
