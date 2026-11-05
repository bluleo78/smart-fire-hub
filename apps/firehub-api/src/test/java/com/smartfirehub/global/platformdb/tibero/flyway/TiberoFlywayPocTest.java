package com.smartfirehub.global.platformdb.tibero.flyway;

import static com.smartfirehub.global.platformdb.tibero.flyway.TiberoFlywayPocAccount.evidence;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.RepairResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.sql.oracle.OracleStorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Flyway Tibero 플러그인과 JobRunr Oracle 저장소를 Tibero 실서버에서 재는 PoC(WD-488).
 *
 * <p>무엇을: 이슈 범위의 명령(migrate·info·validate·repair·baseline·clean)과 DDL 암묵 커밋에서의 실패 복구 순서, Spring
 * Boot 자동 구성의 플러그인 인식·{@code {vendor}} 해소, JobRunr 작업 등록→실패→재시도→성공 1회전을 실제로 돌리고 그 결과를 {@link
 * TiberoFlywayPocAccount#EVIDENCE} 에 남긴다.
 *
 * <p>왜 시험마다 계정을 새로 만드나: Tibero 는 DDL 이 트랜잭션에 묶이지 않아 실패 시험이 반쯤 적용된 스키마를 남긴다. 앞 시험의 잔재가 뒤 시험의 판정을 흐리지
 * 않게 시험마다 PoC 계정(WD488_POC_*)을 새로 만들고 지운다.
 *
 * <p>실행 조건: {@code TIBERO_TEST_HOST} 가 있을 때만(공유 개발 서버). 일반 PG 회귀에서는 건너뛴다.
 */
@EnabledIfEnvironmentVariable(named = "TIBERO_TEST_HOST", matches = ".+")
class TiberoFlywayPocTest {

  private static final String COMMON = "classpath:tibero-poc/flyway/common";

  private TiberoFlywayPocAccount account;

  @BeforeEach
  void createAccount() {
    account = TiberoFlywayPocAccount.create(3);
  }

  @AfterEach
  void dropAccount() {
    if (account != null) account.close();
  }

  /** 공통 설정: PoC 계정 풀·스키마 고정·clean 기본 비활성(운영과 같다). */
  private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String... locations) {
    return Flyway.configure()
        .dataSource(account.dataSource())
        .schemas(account.name())
        .locations(locations);
  }

  private static String states(Flyway flyway) {
    return Arrays.stream(flyway.info().all())
        .map(i -> i.getVersion() + ":" + i.getState())
        .collect(Collectors.joining(", "));
  }

  // ── migrate·info·validate ────────────────────────────

  /** 일반 DDL + PL/SQL 블록(패키지·트리거·멱등 래퍼) 마이그레이션이 적용되고 info·validate 가 동작한다. */
  @Test
  void migrate_info_validate_withPlsqlBlocks() throws Exception {
    Flyway fw = flyway(COMMON, "classpath:tibero-poc/flyway/ok").load();

    var result = fw.migrate();
    evidence("migrate ok: applied=" + result.migrationsExecuted + " db=" + result.database);
    assertThat(result.migrationsExecuted).isEqualTo(2);

    String info = states(fw);
    evidence("info after migrate: " + info);
    assertThat(fw.info().current().getVersion().getVersion()).isEqualTo("2");

    ValidateResult v = fw.validateWithResult();
    evidence("validate ok: " + v.validationSuccessful);
    assertThat(v.validationSuccessful).isTrue();

    // PL/SQL 객체가 유효한 상태로 만들어졌는지(트리거가 실제로 동작하는지까지)
    account.execute("INSERT INTO poc_item (name) VALUES ('  trimmed  ')");
    try (Connection c = account.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT (SELECT COUNT(*) FROM USER_OBJECTS WHERE STATUS <> 'VALID'),"
                    + " (SELECT COUNT(*) FROM poc_item WHERE name = 'trimmed') FROM DUAL")) {
      rs.next();
      evidence("plsql invalid objects=" + rs.getLong(1) + " trigger trimmed rows=" + rs.getLong(2));
      assertThat(rs.getLong(1)).isZero();
      assertThat(rs.getLong(2)).isEqualTo(1);
    }
    assertThat(account.tableExists("POC_IDEMPOTENT")).isTrue();

    // 다시 migrate 하면 할 일이 없다.
    assertThat(fw.migrate().migrationsExecuted).isZero();
  }

  /** 적용된 파일이 바뀌면 validate 가 실패하고, repair 가 체크섬을 다시 맞춘다. */
  @Test
  void validate_detectsChecksumChange_andRepairRealigns() {
    flyway(COMMON, "classpath:tibero-poc/flyway/ok").load().migrate();

    Flyway changed = flyway(COMMON, "classpath:tibero-poc/flyway/ok-modified").load();
    ValidateResult v = changed.validateWithResult();
    evidence(
        "validate after checksum change: ok="
            + v.validationSuccessful
            + " errors="
            + v.invalidMigrations.stream()
                .map(m -> m.version + " " + m.errorDetails.errorCode)
                .collect(Collectors.joining(",")));
    assertThat(v.validationSuccessful).isFalse();

    RepairResult r = changed.repair();
    evidence(
        "repair realigned="
            + r.migrationsAligned.size()
            + " removed="
            + r.migrationsRemoved.size());
    assertThat(changed.validateWithResult().validationSuccessful).isTrue();
  }

  // ── 실패 복구 ─────────────────────────────────────────

  /**
   * DDL 암묵 커밋 때문에 반쯤 적용된 마이그레이션의 복구 순서를 잰다.
   *
   * <p>① V2(성공 DDL A + 실패 DDL B) 적용 실패 → ② 이력 상태 → ③ 그대로 재실행 → ④ repair → ⑤ 원래 파일로 재실행(A 가 이미 있어
   * 실패할 것) → ⑥ 멱등하게 고친 V2 로 repair 후 재실행.
   */
  @Test
  void halfAppliedMigration_recoverySequence() throws Exception {
    Flyway broken = flyway(COMMON, "classpath:tibero-poc/flyway/fail").load();

    // ①
    assertThatThrownBy(broken::migrate)
        .satisfies(e -> evidence("① migrate failed: " + firstLine(e)));
    // ②
    evidence(
        "② after failure: info="
            + states(broken)
            + " half_a="
            + account.tableExists("HALF_A")
            + " half_b="
            + account.tableExists("HALF_B"));
    assertThat(account.tableExists("HALF_A")).as("DDL A 는 암묵 커밋으로 남는다").isTrue();
    assertThat(account.tableExists("HALF_B")).isFalse();

    // ③
    String rerun;
    try {
      rerun = "migrated " + broken.migrate().migrationsExecuted;
    } catch (RuntimeException e) {
      rerun = "failed: " + firstLine(e);
    }
    evidence("③ rerun without repair: " + rerun);

    // ④
    RepairResult r = broken.repair();
    evidence(
        "④ repair removed="
            + r.migrationsRemoved.size()
            + " realigned="
            + r.migrationsAligned.size()
            + " info="
            + states(broken));

    // ⑤
    String again;
    try {
      again = "migrated " + broken.migrate().migrationsExecuted;
    } catch (RuntimeException e) {
      again = "failed: " + firstLine(e);
    }
    evidence("⑤ rerun original file after repair: " + again);

    // ⑥
    Flyway fixed = flyway(COMMON, "classpath:tibero-poc/flyway/fail-fixed").load();
    RepairResult r2 = fixed.repair();
    evidence("⑥ repair before fixed rerun removed=" + r2.migrationsRemoved.size());
    var ok = fixed.migrate();
    evidence(
        "⑥ fixed rerun migrated="
            + ok.migrationsExecuted
            + " info="
            + states(fixed)
            + " half_b="
            + account.tableExists("HALF_B"));
    assertThat(account.tableExists("HALF_B")).isTrue();
    assertThat(fixed.validateWithResult().validationSuccessful).isTrue();
  }

  // ── baseline ──────────────────────────────────────────

  /** 이력 없는 비어 있지 않은 스키마에서 baselineOnMigrate(80) 는 V80 이하를 건너뛰고 V81 만 적용한다(앱 설정과 같은 값). */
  @Test
  void baselineOnMigrate_nonEmptySchema_appliesOnlyAfterBaseline() throws Exception {
    account.execute("CREATE TABLE preexisting (id NUMBER PRIMARY KEY)");
    Flyway fw =
        flyway("classpath:tibero-poc/flyway/baseline")
            .baselineOnMigrate(true)
            .baselineVersion("80")
            .load();

    var result = fw.migrate();
    evidence("baseline migrate applied=" + result.migrationsExecuted + " info=" + states(fw));
    assertThat(account.tableExists("AFTER_BASELINE")).isTrue();
    assertThat(account.tableExists("MUST_NOT_EXIST")).isFalse();
  }

  /** 비어 있는 스키마는 baseline 없이 처음부터 적용된다(설치 시점 기준선 경로). 비어 있음 판정이 Tibero 보정판으로 맞는지 본다. */
  @Test
  void emptySchema_isDetectedEmpty_andMigratesFromScratch() {
    Flyway fw =
        flyway("classpath:tibero-poc/flyway/baseline")
            .baselineOnMigrate(true)
            .baselineVersion("80")
            .load();
    var result = fw.migrate();
    evidence("empty schema migrate applied=" + result.migrationsExecuted + " info=" + states(fw));
    assertThat(result.migrationsExecuted).isEqualTo(2);
  }

  // ── clean ─────────────────────────────────────────────

  /** clean 은 기본값(cleanDisabled=true, 운영 설정)에서 거부된다. */
  @Test
  void clean_isRefusedByDefault() throws Exception {
    Flyway fw = flyway(COMMON).load();
    fw.migrate();
    assertThatThrownBy(fw::clean).satisfies(e -> evidence("clean default: " + firstLine(e)));
    assertThat(account.tableExists("POC_ITEM")).isTrue();
  }

  /**
   * clean 을 켜면 PoC 계정의 객체가 모두 지워진다. 공유 서버이므로 실행 직전에 접속 계정이 PoC 계정인지 확인하고, 대상 스키마도 PoC 계정 하나로 고정한다.
   */
  @Test
  void clean_whenEnabled_dropsAllPocObjects() throws Exception {
    flyway(COMMON, "classpath:tibero-poc/flyway/ok").load().migrate();
    long before = account.objectCount();

    try (Connection c = account.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT USER FROM DUAL")) {
      rs.next();
      assertThat(rs.getString(1)).as("clean 은 PoC 계정에서만").isEqualTo(account.name());
    }
    Flyway fw = flyway(COMMON, "classpath:tibero-poc/flyway/ok").cleanDisabled(false).load();
    String outcome;
    try {
      var r = fw.clean();
      outcome = "ok schemasCleaned=" + r.schemasCleaned;
    } catch (RuntimeException e) {
      outcome = "failed: " + firstLine(e);
    }
    long after = account.objectCount();
    evidence("clean enabled: " + outcome + " objects before=" + before + " after=" + after);
    assertThat(after).isZero();

    // clean 뒤 처음부터 다시 migrate 할 수 있어야 설치 기준선의 "clean 후 재실행" 복구가 성립한다.
    assertThat(fw.migrate().migrationsExecuted).isEqualTo(2);
  }

  /**
   * clean 이 다루지 않는 객체 종류(구체화 뷰)가 있으면 아무것도 지우지 않고 실패한다 — 반쯤 지운 채 끝나지 않게. 기존 종류 목록도 증거로 남긴다(Tibero 가
   * IDENTITY·LOB 등을 어떤 종류 이름으로 보이는지).
   */
  @Test
  void clean_unknownObjectType_failsBeforeDroppingAnything() throws Exception {
    flyway(COMMON).load().migrate();
    evidence("object types after V1 migrate: " + objectTypes());
    account.execute("CREATE MATERIALIZED VIEW poc_mv AS SELECT id, name FROM poc_item");
    long before = account.objectCount();
    evidence("object types with mview: " + objectTypes());

    Flyway fw = flyway(COMMON).cleanDisabled(false).load();
    assertThatThrownBy(fw::clean).satisfies(e -> evidence("clean with mview: " + firstLine(e)));
    assertThat(account.objectCount()).as("아무것도 지우지 않았다").isEqualTo(before);
  }

  private String objectTypes() throws Exception {
    try (Connection c = account.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT OBJECT_TYPE, COUNT(*) FROM USER_OBJECTS GROUP BY OBJECT_TYPE ORDER BY 1")) {
      StringBuilder b = new StringBuilder();
      while (rs.next()) b.append(rs.getString(1)).append('=').append(rs.getLong(2)).append(' ');
      return b.toString().strip();
    }
  }

  /** Flyway 가 없는 스키마(= 계정)를 만들지 않는다 — Oracle 구현은 고정 비밀번호로 CREATE USER 를 한다. */
  @Test
  void missingSchema_isNotCreatedByFlyway() throws Exception {
    String missing =
        "WD488_NOSUCH_" + account.name().substring(TiberoFlywayPocAccount.ACCOUNT_PREFIX.length());
    Flyway fw =
        Flyway.configure()
            .dataSource(account.dataSource())
            .schemas(account.name(), missing)
            .locations(COMMON)
            .load();
    assertThatThrownBy(fw::migrate)
        .satisfies(e -> evidence("missing schema migrate: " + firstLine(e)));
    assertThat(account.userExists(missing)).as("공유 서버에 계정이 생기지 않는다").isFalse();
  }

  // ── Spring Boot 자동 구성 ─────────────────────────────

  /**
   * Spring Boot 의 FlywayAutoConfiguration 이 Tibero DataSource 에서 ServiceLoader 로 플러그인을 잡고 기동 시
   * migrate 하는지, {@code {vendor}} 자리표시자가 무엇으로 풀리는지, PG 전용 설정이 무해한지 본다.
   */
  @Test
  void springBootAutoConfiguration_picksPluginAndResolvesVendor() throws Exception {
    // 컨텍스트가 닫힐 때 Hikari 풀까지 닫지 않도록 close 없는 위임 DataSource 로 넘긴다(풀은 @AfterEach 가 닫는다).
    DataSource ds = new DelegatingDataSource(account.dataSource());
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
        .withBean(DataSource.class, () -> ds)
        .withPropertyValues(
            "spring.flyway.locations=classpath:tibero-poc/flyway/vendor/{vendor}",
            "spring.flyway.schemas=" + account.name(),
            "spring.flyway.baseline-on-migrate=true",
            "spring.flyway.baseline-version=80",
            "spring.flyway.postgresql.transactional-lock=false")
        .run(
            ctx -> {
              String failure =
                  ctx.getStartupFailure() == null ? "none" : firstLine(ctx.getStartupFailure());
              evidence("spring boot startup failure: " + failure);
              assertThat(ctx).hasNotFailed();
              Flyway fw = ctx.getBean(Flyway.class);
              evidence(
                  "spring boot databaseType="
                      + fw.getConfiguration().getDatabaseType().getName()
                      + " locations="
                      + Arrays.toString(fw.getConfiguration().getLocations())
                      + " info="
                      + states(fw));
              assertThat(fw.getConfiguration().getDatabaseType())
                  .isInstanceOf(TiberoFlywayDatabaseType.class);
              // 실측: Spring Boot 3.4 의 DatabaseDriver 에 Tibero 가 없어(UNKNOWN) {vendor} 가 글자 그대로 남는다.
              assertThat(fw.getConfiguration().getLocations()[0].getDescriptor())
                  .contains("{vendor}");
            });
    evidence(
        "vendor tables: tibero="
            + account.tableExists("VENDOR_TIBERO")
            + " unknown="
            + account.tableExists("VENDOR_UNKNOWN"));
    // 그래서 어느 쪽 마이그레이션도 적용되지 않고 기동은 "성공"한다 — 설치본이 빈 스키마로 뜨는 함정.
    assertThat(account.tableExists("VENDOR_TIBERO")).isFalse();
    assertThat(account.tableExists("VENDOR_UNKNOWN")).isFalse();

    // {vendor} 가 풀리지 않아 위치가 비면 조용히 성공한다 — fail-on-missing-locations 로 기동 실패로 바꿀 수 있는지 본다.
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
        .withBean(DataSource.class, () -> ds)
        .withPropertyValues(
            "spring.flyway.locations=classpath:tibero-poc/flyway/vendor/{vendor}",
            "spring.flyway.schemas=" + account.name(),
            "spring.flyway.fail-on-missing-locations=true")
        .run(
            ctx -> {
              evidence(
                  "spring boot fail-on-missing-locations: failure="
                      + (ctx.getStartupFailure() == null
                          ? "none"
                          : firstLine(ctx.getStartupFailure())));
              assertThat(ctx).hasFailed();
            });

    // 명시 경로(설치본 프로필에서 쓸 방식)는 그대로 동작한다.
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
        .withBean(DataSource.class, () -> ds)
        .withPropertyValues(
            "spring.flyway.locations=classpath:tibero-poc/flyway/vendor/tibero",
            "spring.flyway.schemas=" + account.name(),
            "spring.flyway.fail-on-missing-locations=true")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              evidence("spring boot explicit location info=" + states(ctx.getBean(Flyway.class)));
            });
    assertThat(account.tableExists("VENDOR_TIBERO")).isTrue();
  }

  // ── JobRunr ───────────────────────────────────────────

  /**
   * JobRunr OracleStorageProvider 를 Tibero 에 직접 만들고(테이블 생성 포함) 작업 등록 → 실행(실패) → 재시도 → 성공 1회전을 확인한다.
   *
   * <p>JVM 기본 시간대를 UTC 로 두고 돈다 — 운영 Docker 와 같다(build.gradle.kts bootRun 주석, WD-476). 이 조건에서는 PG
   * 대조군({@link JobRunrRetryCycleControlPgTest})과 똑같이 첫 실패가 작업이 던진 예외로 저장되고 재시도에서 성공한다. 테스트 JVM 의
   * 기본값인 KST 에서는 Tibero 드라이버가 Instant 를 KST 벽시계로 저장해 하트비트·FAILED 저장이 어긋난다({@link
   * #probe_jobRunrHeartbeat_onKstJvm}).
   */
  @Test
  void jobRunr_enqueue_fail_retry_succeed() throws Exception {
    java.util.TimeZone original = java.util.TimeZone.getDefault();
    try {
      java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
      OracleStorageProvider storage = new OracleStorageProvider(account.dataSource());
      try {
        evidence("jobrunr tables created: objects=" + account.objectCount());
        for (JobRunrRetryCycle.Outcome o : JobRunrRetryCycle.run(storage)) {
          evidence("jobrunr tibero(UTC jvm) " + o);
          assertThat(o.state()).isEqualTo(StateName.SUCCEEDED);
          assertThat(o.attempts()).isEqualTo(2);
          assertThat(o.failureRecordedAsThrown()).as("FAILED 가 작업이 던진 예외로 저장된다").isTrue();
        }
      } finally {
        storage.close();
      }
    } finally {
      java.util.TimeZone.setDefault(original);
    }
  }

  /**
   * 탐침: JVM 기본 시간대가 UTC 가 아닐 때(KST) JobRunr 서버 하트비트가 Tibero 에 어떻게 저장되는지 잰다.
   *
   * <p>실측(2026-11-04): DB 시계는 UTC 인데 하트비트는 KST 벽시계(+9시간)로 저장되고, 서버가 약 20초(폴링 5초 × 4) 만에 자기 자신을 시간
   * 초과로 지운 뒤 "No servers available" 로 멈춘다. 같은 원인으로 FAILED 저장이 갱신 0건이 되고 고아 감지가 대신 실패 처리한다. JVM 을
   * UTC 로 돌리면 사라진다({@link #jobRunr_enqueue_fail_retry_succeed}). 증거용 탐침이라 판정 단언은 두지 않는다.
   */
  @Test
  void probe_jobRunrHeartbeat_onKstJvm() throws Exception {
    java.util.TimeZone original = java.util.TimeZone.getDefault();
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"));
    try {
      heartbeatProbe();
    } finally {
      java.util.TimeZone.setDefault(original);
    }
  }

  private void heartbeatProbe() throws Exception {
    OracleStorageProvider storage = new OracleStorageProvider(account.dataSource());
    org.jobrunr.utils.mapper.jackson.JacksonJsonMapper json =
        new org.jobrunr.utils.mapper.jackson.JacksonJsonMapper();
    storage.setJobMapper(new org.jobrunr.jobs.mappers.JobMapper(json));
    var server =
        new org.jobrunr.server.BackgroundJobServer(
            storage,
            json,
            null,
            org.jobrunr.server.BackgroundJobServerConfiguration
                .usingStandardBackgroundJobServerConfiguration()
                .andWorkerCount(1)
                .andPollInterval(java.time.Duration.ofSeconds(5)));
    try {
      server.start();
      for (int i = 0; i < 6; i++) {
        Thread.sleep(5_000);
        try (Connection c = account.dataSource().getConnection();
            Statement st = c.createStatement();
            ResultSet rs =
                st.executeQuery(
                    "SELECT id, TO_CHAR(firstHeartbeat, 'HH24:MI:SS.FF3'),"
                        + " TO_CHAR(lastHeartbeat, 'HH24:MI:SS.FF3'), running,"
                        + " TO_CHAR(SYSTIMESTAMP, 'HH24:MI:SS TZH:TZM') FROM"
                        + " jobrunr_backgroundjobservers")) {
          StringBuilder b = new StringBuilder();
          while (rs.next()) {
            b.append("[first=")
                .append(rs.getString(2))
                .append(" last=")
                .append(rs.getString(3))
                .append(" running=")
                .append(rs.getString(4))
                .append(" db=")
                .append(rs.getString(5))
                .append("]");
          }
          evidence(
              "heartbeat t+" + (i + 1) * 5 + "s jvm=" + java.time.Instant.now() + " rows=" + b);
        }
      }
    } finally {
      server.stop();
      storage.close();
    }
  }

  /**
   * 탐침: JobRunr 의 낙관적 잠금 UPDATE({@code WHERE id = ? AND version = ?})가 CLOB 에 긴 문자열을 바인딩할 때 갱신 건수를
   * 바르게 돌려주는지 잰다. FAILED 상태 저장(스택 트레이스가 든 긴 JSON)이 갱신 0건으로 보고된 원인을 가르기 위해서다.
   */
  @Test
  void probe_clobUpdateCount_byLength() throws Exception {
    account.execute(
        "CREATE TABLE probe_job (id VARCHAR(36) PRIMARY KEY, version NUMBER, js CLOB, state"
            + " VARCHAR(36))");
    StringBuilder report = new StringBuilder();
    String[] samples = {
      "y".repeat(100),
      "y".repeat(40_000),
      "한".repeat(10),
      "한".repeat(1_000),
      "한".repeat(1_400),
      "한".repeat(2_000),
      "y".repeat(3_000) + "한글 메시지",
      "y".repeat(5_000) + "한글 메시지"
    };
    for (String value : samples) {
      int len = value.length();
      String id = UUID.randomUUID().toString();
      try (Connection c = account.dataSource().getConnection()) {
        try (var ps = c.prepareStatement("INSERT INTO probe_job VALUES (?, 1, ?, 'PROCESSING')")) {
          ps.setString(1, id);
          ps.setString(2, "x");
          ps.executeUpdate();
        }
        int updated;
        try (var ps =
            c.prepareStatement(
                "UPDATE probe_job SET version = ?, js = ?, state = ? WHERE id = ? AND version = ?")) {
          ps.setInt(1, 2);
          ps.setString(2, value);
          ps.setString(3, "FAILED");
          ps.setString(4, id);
          ps.setInt(5, 1);
          updated = ps.executeUpdate();
        }
        String state;
        long stored;
        try (var ps =
            c.prepareStatement(
                "SELECT state, DBMS_LOB.GETLENGTH(js) FROM probe_job WHERE id = ?")) {
          ps.setString(1, id);
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            state = rs.getString(1);
            stored = rs.getLong(2);
          }
        }
        report
            .append(len)
            .append(":updated=")
            .append(updated)
            .append(",state=")
            .append(state)
            .append(",len=")
            .append(stored)
            .append("; ");
      }
    }
    evidence("probe clob update count " + report);
  }

  private static String firstLine(Throwable e) {
    Throwable t = e;
    StringBuilder b = new StringBuilder(e.getClass().getSimpleName());
    // 원인 사슬의 첫 줄만 모은다(드라이버 오류 코드가 깊은 곳에 있다). 비밀값은 마이그레이션 오류에 실리지 않는다.
    for (int i = 0; t != null && i < 4; i++, t = t.getCause()) {
      String m = String.valueOf(t.getMessage()).strip().split("\n")[0];
      b.append(i == 0 ? ": " : " <- ").append(m.length() > 220 ? m.substring(0, 220) : m);
    }
    return b.toString();
  }
}
