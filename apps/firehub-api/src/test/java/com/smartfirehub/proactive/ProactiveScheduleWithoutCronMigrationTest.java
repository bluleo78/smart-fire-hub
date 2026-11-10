package com.smartfirehub.proactive;

import static com.smartfirehub.jooq.Tables.PROACTIVE_JOB;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * V158 데이터 마이그레이션 검증(WD-607) — 주기 없이 「켜짐」으로 저장된 정기(SCHEDULE) 작업을 끄고, 빈 시간대를 기본값으로 되돌린다.
 *
 * <p>Testcontainers DB 는 V158 적용 시점에 작업이 0건이라 Flyway 결과만 보면 공허하다. 그래서 검증 도입 전 데이터와 같은 행(주기 없는 켜진 정기
 * 작업 등)을 리포지토리를 거치지 않고 심고, <b>V158 파일을 그대로</b> 소유자 롤로 다시 실행한다(GraphOntologySourceBackfillTest 와 같은
 * 방식). 운영 Flyway 도 소유자 롤(RLS 우회 — proactive_job 은 FORCE RLS 가 아니다, V104)로 돈다. 이 테스트가 소유자 롤에서 UPDATE
 * 가 실제로 적용되는지도 함께 확인한다.
 */
class ProactiveScheduleWithoutCronMigrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  private Long ownerId;

  @AfterEach
  void tearDown() {
    if (ownerId != null) {
      inTenantFixture(
          () -> dsl.deleteFrom(PROACTIVE_JOB).where(PROACTIVE_JOB.USER_ID.eq(ownerId)).execute());
      TenantRlsTestSupport.deleteUser(dsl, ownerId);
    }
  }

  @Test
  void v158_disablesEnabledScheduleJobsWithoutCron_only() throws Exception {
    ownerId = TenantRlsTestSupport.insertUser(dsl, "wd607_mig_");
    // 좀비(끄는 대상): 트리거 SCHEDULE·트리거 NULL(기본 SCHEDULE) + cron NULL·빈 값·공백 + 켜짐
    long nullCron = insertJob("SCHEDULE", null, true);
    long emptyCron = insertJob("SCHEDULE", "", true);
    long blankCron = insertJob("SCHEDULE", "   ", true);
    long nullTrigger = insertJob(null, null, true);
    // 그대로 둘 것: 주기 있는 정기 작업, 이상 탐지 트리거가 살아 있는 BOTH·ANOMALY
    long withCron = insertJob("SCHEDULE", "0 0 9 * * *", true);
    long bothNoCron = insertJob("BOTH", null, true);
    long anomalyNoCron = insertJob("ANOMALY", null, true);

    DSLContext owner = DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
    String migration = loadMigration();
    owner.execute(migration);
    owner.execute(migration); // 다시 돌려도 결과가 같다(멱등)

    assertThat(enabled(nullCron)).as("cron NULL").isFalse();
    assertThat(enabled(emptyCron)).as("cron 빈 값").isFalse();
    assertThat(enabled(blankCron)).as("cron 공백").isFalse();
    assertThat(enabled(nullTrigger)).as("트리거 NULL(=SCHEDULE)").isFalse();
    assertThat(enabled(withCron)).as("주기 있는 정기 작업은 그대로").isTrue();
    assertThat(enabled(bothNoCron)).as("BOTH 는 이상 탐지가 살아 있어 그대로").isTrue();
    assertThat(enabled(anomalyNoCron)).as("ANOMALY 는 그대로").isTrue();
  }

  /**
   * 예전 수정 경로가 남긴 빈 값·공백 시간대는 기본 시간대(Asia/Seoul)로 되돌리고, 정상 시간대는 그대로 둔다(WD-607). 컬럼이 NOT NULL 이라 NULL
   * 은 심을 수 없다.
   */
  @Test
  void v158_resetsBlankTimezoneToDefault_only() throws Exception {
    ownerId = TenantRlsTestSupport.insertUser(dsl, "wd607_tz_");
    long emptyTz = insertJob("ANOMALY", null, false, "");
    long blankTz = insertJob("ANOMALY", null, false, "   ");
    long londonTz = insertJob("SCHEDULE", "0 0 9 * * *", true, "Europe/London");

    DSLContext owner = DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
    String migration = loadMigration();
    owner.execute(migration);
    owner.execute(migration); // 다시 돌려도 결과가 같다(멱등)

    assertThat(timezone(emptyTz)).as("빈 시간대").isEqualTo("Asia/Seoul");
    assertThat(timezone(blankTz)).as("공백 시간대").isEqualTo("Asia/Seoul");
    assertThat(timezone(londonTz)).as("정상 시간대는 그대로").isEqualTo("Europe/London");
  }

  /** 검증을 거치지 않은 옛 행처럼 작업을 직접 심는다(기본 테넌트, 시간대는 Asia/Seoul). */
  private long insertJob(String triggerType, String cron, boolean enabled) {
    return insertJob(triggerType, cron, enabled, "Asia/Seoul");
  }

  /** 시간대까지 정해 옛 행처럼 작업을 직접 심는다(기본 테넌트). */
  private long insertJob(String triggerType, String cron, boolean enabled, String timezone) {
    return inTenantFixture(
        () ->
            dsl.insertInto(PROACTIVE_JOB)
                .set(PROACTIVE_JOB.USER_ID, ownerId)
                .set(PROACTIVE_JOB.NAME, "wd607 " + triggerType + " [" + cron + "]")
                .set(PROACTIVE_JOB.PROMPT, "프롬프트")
                .set(PROACTIVE_JOB.CRON_EXPRESSION, cron)
                .set(PROACTIVE_JOB.ENABLED, enabled)
                .set(PROACTIVE_JOB.TRIGGER_TYPE, triggerType)
                .set(PROACTIVE_JOB.TIMEZONE, timezone)
                .returning(PROACTIVE_JOB.ID)
                .fetchOne()
                .getId());
  }

  /** 소유자 롤로 읽는다 — 테넌트 컨텍스트와 무관하게 실제 저장값을 본다. */
  private boolean enabled(long jobId) {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES)
        .select(PROACTIVE_JOB.ENABLED)
        .from(PROACTIVE_JOB)
        .where(PROACTIVE_JOB.ID.eq(jobId))
        .fetchOne(PROACTIVE_JOB.ENABLED);
  }

  /** 소유자 롤로 저장된 시간대를 읽는다. */
  private String timezone(long jobId) {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES)
        .select(PROACTIVE_JOB.TIMEZONE)
        .from(PROACTIVE_JOB)
        .where(PROACTIVE_JOB.ID.eq(jobId))
        .fetchOne(PROACTIVE_JOB.TIMEZONE);
  }

  /** V158 파일 전체를 클래스패스에서 읽는다. */
  private String loadMigration() throws IOException {
    try (InputStream in =
        getClass()
            .getResourceAsStream("/db/migration/V158__disable_schedule_jobs_without_cron.sql")) {
      assertThat(in).as("V158 파일을 클래스패스에서 찾지 못했다").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
