package com.smartfirehub.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * V139(WD-108) — 스텝이 지워져도 과거 스텝 실행 기록이 남도록 바꾼 스키마와 백필을 고정한다.
 *
 * <p>Testcontainers DB 는 V139 적용 시점에 스텝 실행 행이 0건이라 "백필 결과가 있다"를 그대로 보면 공허하다. 그래서 리포지토리를 거치지 않은 행
 * ({@code step_name} 이 빈 = V139 이전 데이터와 같은 상태)을 커밋해 두고, <b>V139 파일의 백필 구간을 잘라</b> 소유자 롤로 재실행한다
 * (GraphOntologySourceBackfillTest 와 같은 방식). 운영 Flyway 도 소유자 롤(RLS 우회·GUC 없음)로 돈다. 소유자 커넥션은 다른
 * 트랜잭션의 미커밋 행을 못 보므로 이 클래스는 트랜잭션 테스트가 아니다 — 픽스처는 커밋하고 {@link #tearDown} 에서 지운다.
 */
class PipelineStepExecutionHistoryMigrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  private Long userId;
  private Long pipelineId;

  private DSLContext owner() {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
  }

  @AfterEach
  void tearDown() {
    inTenantFixture(
        () -> {
          // 파이프라인을 지우면 실행 → 스텝 실행이 CASCADE 로 함께 지워진다.
          if (pipelineId != null) {
            dsl.execute("delete from pipeline_step where pipeline_id = ?", pipelineId);
            dsl.execute("delete from pipeline where id = ?", pipelineId);
          }
        });
    if (userId != null) {
      owner().execute("delete from \"user\" where id = ?", userId);
    }
  }

  /** 카탈로그: step_id 는 NULL 허용, FK 는 ON DELETE SET NULL('n'), step_name 스냅샷 컬럼이 있다. */
  @Test
  void 스텝실행_step_id는_NULL허용이고_FK는_SET_NULL이며_step_name_컬럼이_있다() {
    String nullable =
        owner()
            .fetchValue(
                "select is_nullable from information_schema.columns "
                    + "where table_schema = 'public' and table_name = 'pipeline_step_execution' "
                    + "and column_name = 'step_id'")
            .toString();
    assertThat(nullable).isEqualTo("YES");

    Object delType =
        owner()
            .fetchValue(
                "select confdeltype from pg_constraint "
                    + "where conname = 'pipeline_step_execution_step_id_fkey'");
    assertThat(String.valueOf(delType)).as("ON DELETE SET NULL 은 confdeltype 'n'").isEqualTo("n");

    Record col =
        owner()
            .fetchOne(
                "select data_type, character_maximum_length from information_schema.columns "
                    + "where table_schema = 'public' and table_name = 'pipeline_step_execution' "
                    + "and column_name = 'step_name'");
    assertThat(col).as("step_name 스냅샷 컬럼").isNotNull();
    assertThat(col.get(0, String.class)).isEqualTo("character varying");
    assertThat(col.get(1, Integer.class)).as("pipeline_step.name 과 같은 길이").isEqualTo(100);
  }

  /**
   * 백필은 스냅샷이 비어 있는 기존 행을 현재 스텝 이름으로 채우고 재실행해도 같다. 그 뒤 스텝을 지우면 기록은 step_id NULL + 이름 스냅샷으로 남는다(예전
   * CASCADE 였다면 행이 사라진다).
   */
  @Test
  void 백필은_기존_행의_step_name을_현재_스텝이름으로_채우고_스텝을_지워도_기록이_남는다() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    // "user" 는 테넌트 경계 위의 전역 테이블이라 소유자 롤로 바로 만든다(실행 주체 FK 용).
    userId =
        ((Number)
                owner()
                    .fetchValue(
                        "insert into \"user\" (username, password, name, email) "
                            + "values (?, 'pw', 'bf', ?) returning id",
                        "wd108_" + suffix,
                        "wd108_" + suffix + "@example.com"))
            .longValue();

    long[] ids =
        inTenantFixture(
            () -> {
              long p =
                  ((Number)
                          dsl.fetchValue(
                              "insert into pipeline (name, created_by) values (?, ?) returning id",
                              "wd108-backfill-" + suffix,
                              userId))
                      .longValue();
              long step =
                  ((Number)
                          dsl.fetchValue(
                              "insert into pipeline_step (pipeline_id, name, script_type, script_content, step_order) "
                                  + "values (?, '백필 스텝', 'SQL', 'SELECT 1', 0) returning id",
                              p))
                      .longValue();
              long exec =
                  ((Number)
                          dsl.fetchValue(
                              "insert into pipeline_execution (pipeline_id, executed_by, status) "
                                  + "values (?, ?, 'FAILED') returning id",
                              p,
                              userId))
                      .longValue();
              // 리포지토리를 거치지 않는다 — V139 이전 데이터처럼 step_name 이 빈 행을 만든다.
              long se =
                  ((Number)
                          dsl.fetchValue(
                              "insert into pipeline_step_execution (execution_id, step_id, status, error_message, output_rows) "
                                  + "values (?, ?, 'FAILED', 'boom', 7) returning id",
                              exec,
                              step))
                      .longValue();
              return new long[] {p, step, se};
            });
    pipelineId = ids[0];
    long stepId = ids[1];
    long stepExecId = ids[2];

    assertThat(stepNameOf(stepExecId)).as("사전 상태 — 백필 전에는 스냅샷이 비어 있어야 이 테스트가 의미 있다").isNull();

    String backfill = loadBackfill();
    owner().execute(backfill);
    owner().execute(backfill); // 재실행해도 결과가 같다(step_name IS NULL 만 채운다)

    assertThat(stepNameOf(stepExecId)).isEqualTo("백필 스텝");

    // 스텝 삭제 → 기록은 남고 연결만 끊긴다(ON DELETE SET NULL).
    inTenantFixture(() -> dsl.execute("delete from pipeline_step where id = ?", stepId));
    Record row =
        owner()
            .fetchOne(
                "select step_id, step_name, status, error_message, output_rows "
                    + "from pipeline_step_execution where id = ?",
                stepExecId);
    assertThat(row).as("스텝을 지워도 스텝 실행 기록은 지워지지 않는다").isNotNull();
    assertThat(row.get("step_id")).isNull();
    assertThat(row.get("step_name", String.class)).isEqualTo("백필 스텝");
    assertThat(row.get("status", String.class)).isEqualTo("FAILED");
    assertThat(row.get("error_message", String.class)).isEqualTo("boom");
    assertThat(row.get("output_rows", Integer.class)).isEqualTo(7);
  }

  private String stepNameOf(long stepExecId) {
    Object v =
        owner()
            .fetchValue("select step_name from pipeline_step_execution where id = ?", stepExecId);
    return v == null ? null : v.toString();
  }

  /** V139 파일에서 BACKFILL-BEGIN ~ BACKFILL-END 사이(UPDATE 한 문장)만 잘라 읽는다. */
  private String loadBackfill() throws IOException {
    try (InputStream in =
        getClass()
            .getResourceAsStream("/db/migration/V139__pipeline_step_execution_keep_history.sql")) {
      assertThat(in).as("V139 파일을 클래스패스에서 찾지 못했다").isNotNull();
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      int begin = sql.indexOf("-- BACKFILL-BEGIN");
      int end = sql.indexOf("-- BACKFILL-END");
      assertThat(begin).as("백필 시작 표식").isGreaterThanOrEqualTo(0);
      assertThat(end).as("백필 끝 표식").isGreaterThan(begin);
      return sql.substring(begin + "-- BACKFILL-BEGIN".length(), end).trim();
    }
  }
}
