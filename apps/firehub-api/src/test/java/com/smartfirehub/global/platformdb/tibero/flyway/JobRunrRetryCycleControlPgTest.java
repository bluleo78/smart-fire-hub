package com.smartfirehub.global.platformdb.tibero.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.PostgresTestContainer;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoTestEvidence;
import java.sql.Connection;
import java.sql.Statement;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.sql.postgres.PostgresStorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@link TiberoFlywayPocTest#jobRunr_enqueue_fail_retry_succeed} 의 PostgreSQL 대조군(WD-488).
 *
 * <p>같은 {@link JobRunrRetryCycle} 을 테스트 PG 컨테이너의 별도 스키마({@value #SCHEMA})에서 돌린다. PG 에서도 FAILED 저장이
 * 실패하면 JobRunr 쪽 현상, PG 에서는 의도한 예외로 FAILED 가 저장되면 Tibero 고유 현상이다. 스키마는 끝나면 지운다(공용 컨테이너를 더럽히지 않게).
 *
 * <p>Tibero PoC 와 함께만 돈다(실행 시간 약 30초 — 일반 회귀에 넣을 이유가 없다).
 */
@TiberoLive
class JobRunrRetryCycleControlPgTest {

  private static final String SCHEMA = "wd488_jobrunr_ctl";

  @Test
  void retryCycle_onPostgres_recordsThrownException() throws Exception {
    var pg = PostgresTestContainer.INSTANCE;
    DriverManagerDataSource ds =
        new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    exec(ds, "DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    exec(ds, "CREATE SCHEMA " + SCHEMA);
    try {
      PostgresStorageProvider storage = new PostgresStorageProvider(ds, SCHEMA + ".");
      try {
        for (JobRunrRetryCycle.Outcome o : JobRunrRetryCycle.run(storage)) {
          TiberoTestEvidence.write("jobrunr postgres " + o);
          assertThat(o.state()).isEqualTo(StateName.SUCCEEDED);
          assertThat(o.attempts()).isEqualTo(2);
          // 대조의 핵심 근거: PG 에서는 FAILED 가 작업이 던진 예외로 저장된다(고아 감지로 넘어가지 않는다).
          assertThat(o.failureRecordedAsThrown()).isTrue();
        }
      } finally {
        storage.close();
      }
    } finally {
      exec(ds, "DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }
  }

  private static void exec(javax.sql.DataSource ds, String sql) throws Exception {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }
}
