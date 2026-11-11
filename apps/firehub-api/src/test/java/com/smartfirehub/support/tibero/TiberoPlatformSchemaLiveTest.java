package com.smartfirehub.support.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link TiberoPlatformSchema} 하네스 자체의 약속을 실서버로 고정한다(WD-706).
 *
 * <p>무엇을: ① 리포지토리 쪽 트랜잭션은 운영 앱 풀처럼 <b>런타임 계정</b>으로 돈다 ② {@link TenantContext} 가 트랜잭션 시작에 Tibero
 * 컨텍스트로 심기고 끝나면 지워진다 ③ 픽스처용 {@link TiberoPlatformSchema#jdbc()} 는 <b>소유자</b>라 정책을 우회한다.
 *
 * <p>왜: 이후 모든 Tibero 계약 테스트가 이 셋을 전제로 「다른 테넌트 행이 보이지 않는다」를 시험한다. 하네스가 소유자로 리포지토리를 돌리면 정책이 걸리지 않아 격리
 * 시험이 공허하게 통과하고, 픽스처를 런타임으로 넣고 지우면 테넌트가 없을 때 0행이 되어 공유 서버에 행이 쌓인다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoPlatformSchemaLiveTest {

  /** 이 클래스가 넣는 픽스처 행 표식 — tenant.slug, "USER".username, async_job.resource_id. */
  private static final String MARK = "wd706-harness";

  private TiberoPlatformSchema poc;

  /** 런타임 풀 위 JdbcTemplate — {@code tx()} 안에서 쓰면 그 트랜잭션 커넥션(컨텍스트가 심긴 세션)에 참여한다. */
  private JdbcTemplate runtimeJdbc;

  @BeforeAll
  void start() {
    poc = TiberoPlatformSchema.shared();
    runtimeJdbc = new JdbcTemplate(poc.dataSource());
  }

  /** 이 클래스가 넣은 행을 소유자로 지운다(FK 순서: async_job → "USER" → tenant). */
  @AfterEach
  void cleanup() {
    poc.jdbc().update("DELETE FROM async_job WHERE resource_id LIKE ?", MARK + "%");
    poc.jdbc().update("DELETE FROM \"USER\" WHERE username LIKE ?", MARK + "%");
    poc.jdbc().update("DELETE FROM tenant WHERE slug LIKE ?", MARK + "%");
  }

  @Test
  void repositoryRunsAsRuntimeAccount() {
    String user =
        poc.tx().execute(s -> runtimeJdbc.queryForObject("SELECT USER FROM DUAL", String.class));

    assertThat(user).isEqualTo(poc.runtimeName()).isNotEqualTo(poc.ownerName());
  }

  @Test
  void tenantContextReachesSession() throws Exception {
    String sql =
        "SELECT SYS_CONTEXT('"
            + TiberoTenantRlsSchema.shared().contextName()
            + "','TENANT_ID') FROM DUAL";

    String inside =
        TenantContext.runScopedGet(
            42L, () -> poc.tx().execute(s -> runtimeJdbc.queryForObject(sql, String.class)));
    assertThat(inside).as("트랜잭션 시작에 TenantContext 가 컨텍스트로 심겨야 한다").isEqualTo("42");

    // 트랜잭션이 끝나면 매니저가 지운다 — 같은 풀(크기 2)의 커넥션을 트랜잭션 밖에서 빌려 다시 읽는다.
    try (Connection c = poc.dataSource().getConnection()) {
      assertThat(TiberoSql.queryString(c, sql)).as("풀로 돌아간 커넥션에 테넌트가 남으면 안 된다").isNull();
    }
  }

  @Test
  void fixturesBypassPolicy() {
    long tenantA = createTenant("a");
    long tenantB = createTenant("b");
    long userId =
        poc.insertReturningId(
            "INSERT INTO \"USER\"(username, password, name) VALUES (?, 'x', 'H')", MARK + "-user");
    insertAsyncJob(tenantA, userId);
    insertAsyncJob(tenantB, userId);
    String count = "SELECT COUNT(*) FROM async_job WHERE resource_id LIKE '" + MARK + "%'";

    assertThat(poc.jdbc().queryForObject(count, Long.class)).as("소유자는 정책을 우회한다").isEqualTo(2L);
    Long seenByA =
        TenantContext.runScopedGet(
            tenantA, () -> poc.tx().execute(s -> runtimeJdbc.queryForObject(count, Long.class)));
    assertThat(seenByA).as("런타임 + 테넌트 A 는 A 행만 본다").isEqualTo(1L);
  }

  private long createTenant(String suffix) {
    String slug = MARK + "-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
    return poc.insertReturningId("INSERT INTO tenant (slug, name) VALUES (?, ?)", slug, slug);
  }

  private void insertAsyncJob(long tenantId, long userId) {
    poc.jdbc()
        .update(
            "INSERT INTO async_job (id, job_type, \"RESOURCE\", resource_id, user_id, tenant_id)"
                + " VALUES (?, 'TEST', 'TEST', ?, ?, ?)",
            UUID.randomUUID().toString(),
            MARK + "-" + tenantId,
            userId,
            tenantId);
  }
}
