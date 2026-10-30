package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.PipelineDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.StepCursor;
import com.smartfirehub.pipeline.dto.UpdatePipelineRequest;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SQL 스텝 증분 처리({@code {{last_run_at}}})를 실제 PostgreSQL 에 대해 끝에서 끝까지 증명한다.
 *
 * <p>단위 테스트(목)는 "러너가 어떤 순서로 무엇을 부르는가"만 고정한다. 이 테스트는 그와 별개의 질문 — "실제로 <b>변경된 행만</b> 다시 읽고, 출력은 합집합이
 * 되는가" — 에 답한다. 그래서 검증 장치가 {@code count(*)} 가 아니라 <b>출력 행 변조(tamper) 탐침</b>이다: 2회차 전에 출력의 어떤 행을 손으로
 * 바꿔 두면, 그 행의 원천이 <b>변경되지 않았을 때</b>에만 변조가 살아남는다. 치환이 항상 {@code -infinity} 를 내는 결함이면 그 행이 원천 값으로
 * 되돌아가 이 단언이 깨진다(변이로 확인함) — 행 수 단언만으로는 MERGE 의 멱등성 때문에 그 결함이 영원히 보이지 않는다.
 *
 * <p><b>테스트 트랜잭션을 끈다({@code NOT_SUPPORTED}).</b> 파이프라인 실행은 {@code @Async} 라 별도 스레드·커넥션에서 돈다 — 테스트
 * 트랜잭션 안에서 픽스처를 만들면 그 스레드가 미커밋 행을 보지 못한다. 만든 데이터셋·파이프라인은 {@link #tearDown()} 에서 명시적으로 지운다. 이름에 난수를
 * 붙여 공유 test DB 에서 다른 워크트리/테스트와 충돌하지 않게 한다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineIncrementalIntegrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private DatasetService datasetService;
  @Autowired private PipelineService pipelineService;
  @Autowired private PipelineExecutionService executionService;
  @Autowired private PipelineStepRepository stepRepository;

  /** SELECT * 자동 임시 데이터셋 시나리오에서 생성된 임시 데이터셋을 찾아 정리하기 위해 쓴다. */
  @Autowired private TempDatasetService tempDatasetService;

  /** 늦은 커밋 시나리오에서 실행기와 같은 롤로 직접 접속하기 위한 값들(하드코딩 금지 — 파생 규약을 그대로 쓴다). */
  @Value("${spring.datasource.url}")
  private String jdbcUrl;

  @Value("${app.pipeline.role-password-secret}")
  private String rolePasswordSecret;

  private static final int SEED_ROWS = 200;
  private static final String STEP_NAME = "incremental-step";

  private String suffix;
  private String srcTable;
  private String outTable;
  private Long srcDatasetId;
  private Long outDatasetId;
  private Long pipelineId;
  private Long userId;

  /** 원천 SELECT — 경계는 반드시 {@code >=} 다. {@code >} 면 책갈피와 같은 시각의 행을 영원히 건너뛴다. */
  private String incrementalSql() {
    return "SELECT code, name FROM "
        + DataSchema.qualify(srcTable)
        + " WHERE _updated_at >= {{last_run_at}}";
  }

  @BeforeEach
  void setUpFixtures() {
    suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    srcTable = "inc_src_" + suffix;
    outTable = "inc_out_" + suffix;

    userId =
        inTenantFixture(
            () ->
                dsl.insertInto(USER)
                    .set(USER.USERNAME, "inc_user_" + suffix)
                    .set(USER.PASSWORD, "password")
                    .set(USER.NAME, "Incremental Test User")
                    .set(USER.EMAIL, "inc_" + suffix + "@example.com")
                    .returning(USER.ID)
                    .fetchOne()
                    .getId());

    // 보안 등급(S2): 파이프라인 SQL 스텝은 저장·실행 시 사용자 자격으로 판정된다 — ACTIVE 멤버십 + USER 역할(기본 '내부' 자격)이
    // 있어야 기본 등급('내부') 데이터셋을 볼 수 있다.
    inTenantFixture(
        () -> TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID));
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID, "USER");

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("code", "Code", "TEXT", null, false, false, null, true),
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null, false));

    // createDataset 은 스스로 트랜잭션을 연다 — inTenantFixture 안에 넣지 않는다(경계 규칙).
    DatasetDetailResponse src =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Inc Src " + suffix, srcTable, null, null, "TABLE", "SOURCE", columns, null),
            userId);
    srcDatasetId = src.id();

    DatasetDetailResponse out =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Inc Out " + suffix, outTable, null, null, "TABLE", "DERIVED", columns, null),
            userId);
    outDatasetId = out.id();

    PipelineDetailResponse pipeline =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Inc Pipeline " + suffix,
                "증분 처리 통합 테스트",
                List.of(
                    new PipelineStepRequest(
                        STEP_NAME,
                        "증분 MERGE",
                        "SQL",
                        incrementalSql(),
                        outDatasetId,
                        null,
                        null,
                        "MERGE"))),
            userId);
    pipelineId = pipeline.id();
  }

  @AfterEach
  void tearDown() {
    // 실행 중인 스텝 밑에서 테이블을 지우면 플레이크가 되므로, 정리는 항상 종료 상태 이후에 한다.
    // (각 테스트가 마지막 실행을 반드시 기다리므로 여기서는 메타데이터만 정리하면 된다.)
    if (pipelineId != null) {
      try {
        pipelineService.deletePipelineUnchecked(pipelineId);
      } catch (Exception ignored) {
        // 정리 실패가 본 검증 결과를 가리지 않게 한다.
      }
    }
    for (Long datasetId : new Long[] {outDatasetId, srcDatasetId}) {
      if (datasetId != null) {
        try {
          datasetService.deleteDataset(datasetId);
        } catch (Exception ignored) {
          // 위와 같은 이유.
        }
      }
    }
    if (userId != null) {
      // 파이프라인 실행이 감사 로그를 남기므로 user 를 지우기 전에 그 참조부터 지운다(FK).
      inTenantFixture(
          () -> {
            dsl.execute("DELETE FROM audit_log WHERE user_id = ?", userId);
            dsl.execute("DELETE FROM user_role WHERE user_id = ?", userId);
            TenantRlsTestSupport.deleteMembership(dsl, userId);
            dsl.deleteFrom(USER).where(USER.ID.eq(userId)).execute();
          });
    }
  }

  // ------------------------------------------------------------------ //
  // 시나리오 1: 증분 기본 — 2회차가 "변경된 행만" 읽고 출력은 합집합이 된다
  // ------------------------------------------------------------------ //

  @Test
  void 두번째_실행은_변경된_행만_읽고_출력은_합집합이_된다() {
    seedSource(1, SEED_ROWS);

    Long exec1 = runAndWait("COMPLETED");

    assertThat(outCount()).as("1회차는 전체를 읽는다").isEqualTo(SEED_ROWS);
    assertThat(injectedLastRunAt(exec1)).as("1회차는 책갈피가 없으므로 전체 읽기(null 기록)").isNull();
    OffsetDateTime bookmarkAfterRun1 = cursor().lastRunAt();
    assertThat(bookmarkAfterRun1).as("성공 실행은 책갈피를 남긴다").isNotNull();

    // 변조 탐침 — 원천이 바뀌지 않은 행. 2회차가 이 행을 다시 읽으면 원천 값으로 덮여 변조가 사라진다.
    dsl.execute(
        "UPDATE " + DataSchema.qualify(outTable) + " SET name = 'tampered' WHERE code = 'C0001'");

    // 신규 50행 + 기존 10행 수정 = 변경분 60건.
    seedSource(SEED_ROWS + 1, SEED_ROWS + 50);
    dsl.execute(
        "UPDATE "
            + DataSchema.qualify(srcTable)
            + " SET name = 'updated' WHERE code BETWEEN 'C0100' AND 'C0109'");

    Long exec2 = runAndWait("COMPLETED");

    assertThat(outCount()).as("출력은 합집합이어야 한다(중복도, 초기화도 아니다)").isEqualTo(SEED_ROWS + 50);
    assertThat(nameOf(outTable, "C0100")).as("변경된 행은 반영된다").isEqualTo("updated");
    assertThat(nameOf(outTable, "C0250")).as("신규 행은 삽입된다").isEqualTo("name-0250");

    // 비공허성의 핵심 — 변경되지 않은 행은 2회차가 읽지 않았어야 한다.
    assertThat(nameOf(outTable, "C0001"))
        .as("원천이 바뀌지 않은 행은 2회차가 다시 읽지 않아야 하므로 변조가 살아남는다")
        .isEqualTo("tampered");

    // 2회차가 주입한 값 = 1회차가 남긴 책갈피.
    assertThat(injectedLastRunAt(exec2)).isEqualTo(bookmarkAfterRun1);

    // 주입값 이후 변경분이 실제로 60건임을 원천에서 직접 센다(스텝이 읽은 범위의 실측).
    // 리터럴 조립은 프로덕션과 같은 포맷터를 쓴다 — 마이크로초가 살아남는지까지 이 단언이 함께 본다
    // (정밀도를 잃으면 경계 행이 추가로 섞여 60건이 깨진다).
    Integer changed =
        dsl.fetchOne(
                "SELECT count(*) FROM "
                    + DataSchema.qualify(srcTable)
                    + " WHERE _updated_at >= "
                    + LastRunAtPlaceholder.substitute("{{last_run_at}}", injectedLastRunAt(exec2)))
            .get(0, Integer.class);
    assertThat(changed).as("2회차가 읽은 원천 행은 신규 50 + 수정 10 이어야 한다").isEqualTo(60);
  }

  // ------------------------------------------------------------------ //
  // 시나리오 2: 전체 재생성 예약
  // ------------------------------------------------------------------ //

  @Test
  void 전체_재생성_예약은_출력을_비우고_다시_채운_뒤_해제된다() {
    seedSource(1, SEED_ROWS);
    runAndWait("COMPLETED");

    // 원천에 없는 고아 행 — 전체 재생성이 실제로 출력을 비웠는지 보는 표식.
    dsl.execute(
        "INSERT INTO " + DataSchema.qualify(outTable) + " (code, name) VALUES ('ZZZ', 'orphan')");
    assertThat(outCount()).isEqualTo(SEED_ROWS + 1);

    stepRepository.setFullRebuildPending(stepId(), true);
    assertThat(outCount()).as("예약 자체는 데이터를 건드리지 않는다").isEqualTo(SEED_ROWS + 1);

    runAndWait("COMPLETED");

    assertThat(nameOf(outTable, "ZZZ")).as("전체 재생성은 출력을 비운 뒤 다시 채운다").isNull();
    assertThat(outCount()).isEqualTo(SEED_ROWS);
    assertThat(cursor().fullRebuildPending()).as("성공한 실행만 예약을 해제한다").isFalse();
  }

  @Test
  void 전체_재생성이_실패하면_출력이_보존되고_예약이_유지된다() {
    seedSource(1, SEED_ROWS);
    runAndWait("COMPLETED");

    // 같은 이름·같은 출력으로 재저장 → 책갈피는 이월되고, SQL 만 실행 중 실패하는 형태로 바뀐다.
    // (PK 컬럼 code 는 NOT NULL 이므로 INSERT 단계에서 깨진다 — probe 는 통과한다.)
    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest(
            "Inc Pipeline " + suffix,
            "증분 처리 통합 테스트",
            null,
            List.of(
                new PipelineStepRequest(
                    STEP_NAME,
                    "실행 중 실패",
                    "SQL",
                    "SELECT NULL::text AS code, name FROM "
                        + DataSchema.qualify(srcTable)
                        + " WHERE _updated_at >= {{last_run_at}}",
                    outDatasetId,
                    null,
                    null,
                    "MERGE"))),
        userId);

    stepRepository.setFullRebuildPending(stepId(), true);

    runAndWait("FAILED");

    // 출력 비우기(DELETE)는 본 INSERT 와 같은 트랜잭션이므로 함께 롤백된다 — 출력이 비지 않아야 한다.
    assertThat(outCount()).as("실패한 전체 재생성은 출력을 비운 채로 남기면 안 된다").isEqualTo(SEED_ROWS);
    assertThat(cursor().fullRebuildPending()).as("실패하면 예약이 유지돼 다음 실행이 다시 시도한다").isTrue();
  }

  // ------------------------------------------------------------------ //
  // 시나리오 3: REPLACE 원자성 회귀 (Task 4) — 러너부터 DB 까지 실제로 관통하는 확인
  // ------------------------------------------------------------------ //

  /**
   * REPLACE 스텝의 SQL 이 실행 중 실패하면 출력이 <b>빈 채로 남으면 안 된다</b>.
   *
   * <p>Task 4 는 이 계약을 {@code SqlScriptExecutorSandboxTest}(실행기 단위)와 {@code
   * PipelineAsyncRunnerTest}(목)로 나눠 고정했다. 여기서는 러너 → 실행기 → 실제 테이블까지 한 번에 관통해 "비우기 DELETE 가 본 INSERT
   * 와 같은 트랜잭션에 있다"를 최종 확인한다 — 증분과 무관한 회귀 가드지만 같은 픽스처로 싸게 얻을 수 있다.
   */
  @Test
  void REPLACE_스텝이_실행_중_실패하면_출력이_비지_않는다() {
    dsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(outTable)
            + " (code, name) SELECT 'K' || i, 'keep-' || i FROM generate_series(1, 5) AS i");
    seedSource(1, 10);

    // 플레이스홀더 없는 REPLACE SELECT — PK 컬럼 code 가 NOT NULL 이라 INSERT 단계에서 깨진다
    // (probe 는 통과하므로 "비우기는 실행됐는데 적재가 실패한" 정확한 상황이 된다).
    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest(
            "Inc Pipeline " + suffix,
            "REPLACE 원자성",
            null,
            List.of(
                new PipelineStepRequest(
                    STEP_NAME,
                    "replace 실패",
                    "SQL",
                    "SELECT NULL::text AS code, name FROM " + DataSchema.qualify(srcTable),
                    outDatasetId,
                    null,
                    null,
                    "REPLACE"))),
        userId);

    runAndWait("FAILED");

    assertThat(outCount()).as("실패한 REPLACE 는 출력을 비운 채로 남기면 안 된다").isEqualTo(5);
    assertThat(nameOf(outTable, "K1")).isEqualTo("keep-1");
  }

  /** 출력 지정 하나짜리 SQL 스텝으로 이 파이프라인을 바꿔 저장한다(정식 저장 API — 저장 검증을 거친다). */
  private void saveSingleStep(String sql, String loadStrategy) {
    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest(
            "Inc Pipeline " + suffix,
            "WD-426",
            null,
            List.of(
                new PipelineStepRequest(
                    STEP_NAME, "self", "SQL", sql, outDatasetId, null, null, loadStrategy))),
        userId);
  }

  /**
   * 이슈 재현(WD-426) — REPLACE SELECT 가 출력 자신을 읽으면 비우기 <b>전</b> 출력을 읽는다. 예전에는 비우기 선행 문장 뒤라 빈 테이블을 읽어
   * 0행인 채 「완료」로 끝나 기존 데이터가 조용히 사라졌다.
   *
   * <p>출력에는 PK(code, ux_<table>_pk 유니크 인덱스)가 있다 — 비우기를 본 문장 안 참조하지 않는 CTE 로만 두면 PostgreSQL 이 INSERT 뒤에
   * 실행해 옛 행과 키가 부딪혀(23505) 실패했다(실측). 두 번 돌려 두 번째도 첫 실행 결과(비우기 전)를 읽는지, 행이 늘거나 줄지 않는지 본다. 실행기 끈 로컬
   * 경로(SqlScriptExecutor — 테스트 프로필)로 돈다.
   */
  @Test
  void REPLACE_SELECT_가_자기_출력을_읽으면_비우기_전_데이터를_읽어_다시_채운다() {
    dsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(outTable)
            + " (code, name) SELECT 'K' || i, 'keep-' || i FROM generate_series(1, 50) AS i");
    saveSingleStep(
        "SELECT code, name || '+1' AS name FROM " + DataSchema.qualify(outTable) + " -- 끝 주석",
        "REPLACE");

    runAndWait("COMPLETED");
    assertThat(outCount()).as("행이 그대로 50행 — 비운 테이블을 읽어 0행이 되지 않는다").isEqualTo(50);
    assertThat(nameOf(outTable, "K1")).isEqualTo("keep-1+1");
    assertThat(nameOf(outTable, "K50")).isEqualTo("keep-50+1");

    runAndWait("COMPLETED");
    assertThat(outCount()).as("두 번째 실행도 중복·누락 없이 50행").isEqualTo(50);
    assertThat(nameOf(outTable, "K1")).as("두 번째 실행은 첫 실행 결과를 읽는다").isEqualTo("keep-1+1+1");
  }

  /**
   * 실행 시점 2차 방어(WD-426) — 저장 검증을 거치지 않고 SQL 이 바뀐 REPLACE 사용자 DML(레거시 행·직접 수정)이 출력 자신을 고치면, 비운 테이블을
   * 고쳐 0행으로 「완료」되는 대신 비우기 전에 실패하고 출력은 그대로 남는다.
   */
  @Test
  void REPLACE_사용자_DML_이_자기_출력을_고치면_실행_전에_실패하고_출력이_남는다() {
    dsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(outTable)
            + " (code, name) SELECT 'K' || i, 'keep-' || i FROM generate_series(1, 5) AS i");
    saveSingleStep("SELECT code, name FROM " + DataSchema.qualify(srcTable), "REPLACE");
    // 저장 검증을 우회해 출력을 고치는 DML 로 바꾼다(저장 API 는 이 조합을 거부한다).
    replaceStepSql("UPDATE " + DataSchema.qualify(outTable) + " SET name = name || '+1'");

    Long executionId = runAndWait("FAILED");

    assertThat(stepErrorMessage(executionId))
        .contains("교체(REPLACE) 스텝의 INSERT·UPDATE·DELETE 문은 출력 데이터셋 자신을 읽거나 고칠 수 없습니다");
    assertThat(outCount()).as("거부된 실행은 출력을 비우지 않는다").isEqualTo(5);
    assertThat(nameOf(outTable, "K1")).isEqualTo("keep-1");
  }

  // ------------------------------------------------------------------ //
  // 시나리오 4: 늦은 커밋 — candidate 규칙과 >= 경계
  // ------------------------------------------------------------------ //

  /**
   * 실행 <b>도중</b> 열려 있던 트랜잭션이 나중에 커밋한 행을 다음 실행이 반드시 읽어야 한다.
   *
   * <p>{@code _updated_at} 은 트랜잭션 <b>시작</b> 시각이므로, 미커밋 트랜잭션 T 가 있는 동안 잡은 책갈피가 {@code T.xact_start}
   * 보다 크면 T 가 커밋한 행은 영원히 사라진다. V123 의 {@code fh_incremental_cursor_candidate()} 가 {@code
   * LEAST(clock, min(xact_start))} 를 돌려주어 책갈피 = {@code T.xact_start} 가 되고, 사용자 SQL 의 {@code >=} 가 그
   * 경계 행을 다시 읽는다 — 즉 이 테스트 하나가 candidate 규칙과 {@code >=} 경계를 동시에 고정한다(별도 경계 행 테스트를 두지 않는 이유: {@code
   * UPDATE ... SET _updated_at} 은 트리거가 덮어써 경계를 인위적으로 만들 수 없다).
   */
  @Test
  void 실행_도중_열려있던_트랜잭션이_나중에_커밋한_행도_다음_실행이_읽는다() {
    seedSource(1, 10);

    // 실행기와 같은 테넌트 롤(pipeline_executor_t1)로 별도 연결을 직접 열어 커밋하지 않은 채 파이프라인을
    // 돌린다. 앱 데이터소스(app_tenant)를 쓰면 안 된다 — 후보값 함수가 SECURITY DEFINER 를 잃어도
    // "같은 롤이라 xact_start 가 보이는" 이유로 통과해 결함을 가린다.
    //
    // 레지스트리의 풀을 빌리지 않고 직접 연결하는 이유: test 프로필의 테넌트 풀 max-size 는 1 이라,
    // 이 테스트가 풀 커넥션을 붙들면 파이프라인 실행이 커넥션을 못 얻어 타임아웃으로 FAILED 가 된다(실측).
    String role = TenantPipelineRole.roleName(DEFAULT_TEST_TENANT_ID);
    String password = TenantPipelineRole.password(DEFAULT_TEST_TENANT_ID, rolePasswordSecret);
    try (java.sql.Connection conn = java.sql.DriverManager.getConnection(jdbcUrl, role, password)) {
      conn.setAutoCommit(false);
      try (var st = conn.createStatement()) {
        st.execute(
            "INSERT INTO "
                + DataSchema.qualify(srcTable)
                + " (code, name) VALUES ('LATE', 'late-row')");
      }
      // 커밋하지 않은 상태로 1회차 실행 — LATE 는 보이지 않아야 한다.
      runAndWait("COMPLETED");
      assertThat(nameOf(outTable, "LATE")).as("미커밋 행은 이번 실행에 보이지 않는다").isNull();

      conn.commit();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }

    runAndWait("COMPLETED");

    assertThat(nameOf(outTable, "LATE"))
        .as("실행 도중 열려 있던 트랜잭션이 나중에 커밋한 행은 다음 실행이 반드시 읽어야 한다")
        .isEqualTo("late-row");
  }

  // ------------------------------------------------------------------ //
  // 시나리오 5: SELECT * 자동 임시 데이터셋 — 예약어 컬럼 별칭이 유효한 이름이어야 한다
  // ------------------------------------------------------------------ //

  /**
   * 코드리뷰 HIGH — {@code SELECT *} 스텝(출력 데이터셋 미지정 = 임시 데이터셋 자동 생성)이 실제로 성공해야 한다.
   *
   * <p>V123 가 <b>모든</b> 데이터셋 물리 테이블에 {@code _updated_at} 을 추가했으므로 {@code SELECT *} 결과에는 항상 그 컬럼이 섞여
   * 들어온다. 예약어 별칭이 {@code _updated_at_1} 처럼 밑줄로 시작하면 {@code DataTableService.validateName}({@code
   * ^[a-z][a-z0-9_]*$})이 거부해 {@code InvalidTableNameException} 으로 <b>100% 실패</b>한다. 별칭 헬퍼만 단위로 보면
   * "이름이 바뀌었다"까지밖에 못 보므로, 여기서는 러너 → 임시 데이터셋 생성 → 적재까지 관통해 확인한다.
   */
  @Test
  void SELECT_스타_스텝은_예약어_컬럼을_유효한_이름으로_별칭해_임시_데이터셋을_만든다() {
    seedSource(1, 3);

    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest(
            "Inc Pipeline " + suffix,
            "SELECT * 자동 임시 데이터셋",
            null,
            List.of(
                new PipelineStepRequest(
                    STEP_NAME,
                    "select star",
                    "SQL",
                    "SELECT * FROM " + DataSchema.qualify(srcTable),
                    null, // 출력 데이터셋 미지정 → 임시 데이터셋 자동 생성 경로
                    null,
                    null,
                    "REPLACE"))),
        userId);

    Long tempDatasetId = null;
    try {
      runAndWait("COMPLETED");

      tempDatasetId = tempDatasetService.findExistingTempDataset(stepId()).orElseThrow();
      Long finalTempDatasetId = tempDatasetId;
      List<String> columns =
          inTenantFixture(
              () ->
                  dsl.fetch(
                          "SELECT column_name FROM dataset_column WHERE dataset_id = ?"
                              + " ORDER BY column_order",
                          finalTempDatasetId)
                      .getValues(0, String.class));

      // 개수·순서까지 그대로 고정한다 — 같은 목록이 INSERT 대상 컬럼 매칭에 재사용되므로 하나라도
      // 빠지면 SELECT 식 목록과 어긋나 실행이 깨진다.
      assertThat(columns)
          .as("SELECT * 의 시스템 예약 컬럼은 순서·개수를 유지한 채 유효한 이름으로 별칭돼야 한다")
          .containsExactly("id_1", "import_id_1", "code", "name", "created_at_1", "updated_at_1");
      assertThat(columns)
          .as("모든 컬럼명이 DataTableService.validateName 규칙(^[a-z][a-z0-9_]*$)을 만족해야 한다")
          .allMatch(c -> c.matches("^[a-z][a-z0-9_]*$"));

      String tempTable =
          inTenantFixture(
              () ->
                  (String)
                      dsl.fetchValue(
                          "SELECT table_name FROM dataset WHERE id = ?", finalTempDatasetId));
      Integer loaded =
          dsl.fetchOne("SELECT count(*) FROM " + DataSchema.qualify(tempTable))
              .get(0, Integer.class);
      assertThat(loaded).as("임시 데이터셋에 원천 행이 실제로 적재돼야 한다").isEqualTo(3);
    } finally {
      if (tempDatasetId != null) {
        // 임시 데이터셋은 파이프라인 삭제로 함께 지워지지 않는다 — 공유 test DB 에 물리 테이블이
        // 쌓이지 않게 여기서 직접 지운다.
        try {
          datasetService.deleteDataset(tempDatasetId);
        } catch (Exception ignored) {
          // 정리 실패가 본 검증 결과를 가리지 않게 한다.
        }
      }
    }
  }

  /**
   * 재실행 회귀 고정(WD-229): 같은 {@code SELECT *} 파이프라인을 두 번 실행해도 임시 데이터셋의 {@code id_1} 에 원본 {@code id} 가
   * 채워진다.
   *
   * <p>예전에는 두 번째 실행부터 스텝 출력 id 가 TEMP id 로 채워져 예약어 별칭 경로(id → id_1)가 죽고 {@code id_1} 이 NULL 로 남았다.
   * 지금은 소유한 TEMP 를 미지정과 같게 취급(ownedTempOutput)하므로 별칭 경로를 다시 탄다 — 이를 REPLACE·APPEND 모두에서 실DB 로 고정한다.
   */
  @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] {0}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"REPLACE", "APPEND"})
  void SELECT_스타_스텝을_다시_실행해도_id_1_에_원본_id가_들어간다(String strategy) {
    seedSource(1, 3);

    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest(
            "Inc Pipeline " + suffix,
            "SELECT * 재실행",
            null,
            List.of(
                new PipelineStepRequest(
                    STEP_NAME,
                    "select star rerun",
                    "SQL",
                    "SELECT * FROM " + DataSchema.qualify(srcTable),
                    null,
                    null,
                    null,
                    strategy))),
        userId);

    Long tempDatasetId = null;
    try {
      runAndWait("COMPLETED");
      runAndWait("COMPLETED");

      tempDatasetId = tempDatasetService.findExistingTempDataset(stepId()).orElseThrow();
      Long finalTempDatasetId = tempDatasetId;
      String tempTable =
          inTenantFixture(
              () ->
                  (String)
                      dsl.fetchValue(
                          "SELECT table_name FROM dataset WHERE id = ?", finalTempDatasetId));
      List<Long> aliased =
          dsl.fetch("SELECT DISTINCT id_1 FROM " + DataSchema.qualify(tempTable))
              .getValues(0, Long.class);
      List<Long> sourceIds =
          dsl.fetch("SELECT id FROM " + DataSchema.qualify(srcTable)).getValues(0, Long.class);

      assertThat(aliased).as("재실행 뒤 id_1 에 NULL 이 없어야 한다").doesNotContainNull();
      assertThat(aliased)
          .as("id_1 은 원본 id 집합과 같아야 한다")
          .containsExactlyInAnyOrderElementsOf(sourceIds);
    } finally {
      if (tempDatasetId != null) {
        try {
          datasetService.deleteDataset(tempDatasetId);
        } catch (Exception ignored) {
          // 정리 실패가 본 검증 결과를 가리지 않게 한다.
        }
      }
    }
  }

  // ------------------------------------------------------------------ //
  // 시나리오 6: SQL 끝의 한 줄 주석·세미콜론 (#741)
  // ------------------------------------------------------------------ //

  /**
   * SQL 끝의 한 줄 주석({@code -- ...})·세미콜론이 러너의 래핑(컬럼 probe·MERGE 서브쿼리)을 깨지 않는다(#741).
   *
   * <p>예전 probe 는 사용자 SQL 뒤에 개행 없이 {@code ) AS _probe LIMIT 0} 을 붙여, 끝의 {@code --} 가 닫는 괄호까지 주석으로
   * 만들어 {@code syntax error at end of input} 으로 실패했다. 세미콜론도 괄호 안에 그대로 들어가 같은 식으로 깨졌다. REPLACE(probe
   * → INSERT … SELECT)와 MERGE(probe → {@code MergeSqlBuilder} 서브쿼리) 두 경로를 러너부터 실제 테이블까지 관통해 확인한다 —
   * MERGE 의 개행 처리는 probe 가 먼저 실패해 지금까지 끝까지 실행된 적이 없었다.
   */
  @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] {0} / {1}")
  @org.junit.jupiter.params.provider.MethodSource("trailingCommentCases")
  void 끝이_한줄_주석이나_세미콜론인_SELECT_스텝도_실행된다(String strategy, String tail) {
    seedSource(1, 10);
    String where = "MERGE".equals(strategy) ? " WHERE _updated_at >= {{last_run_at}}" : "";
    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest(
            "Inc Pipeline " + suffix,
            "끝 주석",
            null,
            List.of(
                new PipelineStepRequest(
                    STEP_NAME,
                    "끝 주석 " + strategy,
                    "SQL",
                    "SELECT code, name FROM " + DataSchema.qualify(srcTable) + where + tail,
                    outDatasetId,
                    null,
                    null,
                    strategy))),
        userId);

    runAndWait("COMPLETED");

    assertThat(outCount()).as("끝 주석/세미콜론이 있어도 원천 10행이 그대로 적재돼야 한다").isEqualTo(10);
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments>
      trailingCommentCases() {
    return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of("REPLACE", " -- note"),
        org.junit.jupiter.params.provider.Arguments.of("REPLACE", ";"),
        org.junit.jupiter.params.provider.Arguments.of("REPLACE", "; -- note"),
        org.junit.jupiter.params.provider.Arguments.of("MERGE", " -- note"),
        org.junit.jupiter.params.provider.Arguments.of("MERGE", "; -- note"));
  }

  /**
   * 세미콜론 뒤 블록 주석, 달러 인용·E 문자열 안의 {@code --}/{@code '}/{@code ;} 가 있는 SELECT 스텝도 러너의 래핑(컬럼
   * probe·MERGE 서브쿼리)을 깨지 않는다(#746).
   *
   * <p>예전 {@code stripTrailingSemicolon} 은 작은따옴표와 {@code --} 만 추적해서, {@code ; /* note *}{@code /} 는
   * 주석을 "세미콜론 뒤 코드"로 봐 세미콜론을 남겼고, {@code $$--y;$$} 는 달러 인용 속 {@code --} 를 주석 시작으로 봐 진짜 {@code ;} 까지
   * 건너뛰었고, {@code $$it's$$} 는 달러 인용 속 {@code '} 로 따옴표 상태에 갇혔다 — 셋 다 probe 가 {@code syntax error at
   * or near ";"} 로 실패했다. REPLACE 는 probe 경로, MERGE 는 probe + {@code MergeSqlBuilder} 서브쿼리 경로를 러너부터
   * 실제 테이블까지 관통해 확인한다.
   *
   * <p>WHERE 절은 항상 붙인다(REPLACE 는 {@code WHERE true}, MERGE 는 증분 책갈피) — 꼬리의 {@code AND ...} 가 두 전략에
   * 같은 모양으로 붙게 하려는 것이다. 비교 대상 문자열은 원천에 없는 값이라 행을 거르지 않는다.
   */
  @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] {0} / {1}")
  @org.junit.jupiter.params.provider.MethodSource("lexicalTailCases")
  void 블록_주석이나_달러_인용이_든_SELECT_스텝도_실행된다(String strategy, String tail) {
    seedSource(1, 10);
    String where =
        "MERGE".equals(strategy) ? " WHERE _updated_at >= {{last_run_at}}" : " WHERE true";
    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest(
            "Inc Pipeline " + suffix,
            "어휘 꼬리",
            null,
            List.of(
                new PipelineStepRequest(
                    STEP_NAME,
                    "어휘 꼬리 " + strategy,
                    "SQL",
                    "SELECT code, name FROM " + DataSchema.qualify(srcTable) + where + tail,
                    outDatasetId,
                    null,
                    null,
                    strategy))),
        userId);

    runAndWait("COMPLETED");

    assertThat(outCount()).as("블록 주석/달러 인용 꼬리가 있어도 원천 10행이 그대로 적재돼야 한다").isEqualTo(10);
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> lexicalTailCases() {
    List<String> tails =
        List.of(
            "; /* note */",
            " AND code <> $$--y;$$;",
            " AND code <> $$it's$$;",
            // E 문자열 속 --/;, 세미콜론 뒤 블록·줄 주석. 백슬래시 E 문자열과 중첩 블록 주석은 이제 저장 시 거부된다(아래 테스트)
            " AND code <> E'x''--;'; /* a b */ -- z\n");
    return java.util.stream.Stream.of("REPLACE", "MERGE")
        .flatMap(
            s -> tails.stream().map(t -> org.junit.jupiter.params.provider.Arguments.of(s, t)));
  }

  /**
   * PG·JSqlParser 어휘가 갈리는 꼬리(백슬래시 E 문자열, 중첩 블록 주석)는 저장 시 검증에서 거부된다 — 그 틈에 넣은 테이블 참조를 검증기가 못 보기
   * 때문이다(PgLexicalAmbiguityCheck, 보안 등급 Task 13). 예전에는 위 파라미터 케이스로 실행까지 확인하던 꼬리다.
   */
  @Test
  void 어휘가_모호한_꼬리는_저장_시_거부된다() {
    for (String tail :
        List.of(" AND code <> E'\\'--;';", " AND code <> 'x'; /* a /* nested */ b */ -- z\n")) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  pipelineService.updatePipeline(
                      pipelineId,
                      new UpdatePipelineRequest(
                          "Inc Pipeline " + suffix,
                          "어휘 꼬리",
                          null,
                          List.of(
                              new PipelineStepRequest(
                                  STEP_NAME,
                                  "어휘 꼬리",
                                  "SQL",
                                  "SELECT code, name FROM "
                                      + DataSchema.qualify(srcTable)
                                      + " WHERE true"
                                      + tail,
                                  outDatasetId,
                                  null,
                                  null,
                                  "REPLACE"))),
                      userId))
          .as(tail)
          .isInstanceOf(com.smartfirehub.pipeline.exception.UnsafeSqlException.class);
    }
  }

  // ------------------------------------------------------------------ //
  // 시나리오 7: 같은 증분 스텝의 실행 두 개가 겹친다(WD-223)
  // ------------------------------------------------------------------ //

  /**
   * 같은 증분 APPEND 스텝의 실행 둘이 겹쳐도 같은 구간을 두 번 적재하지 않고, 뒤 실행은 앞 실행 도중 들어온 새 행을 놓치지 않는다.
   *
   * <p>재현 조건(WD-223): 앞 실행이 느린 동안 뒤 실행이 시작되면, 수정 전에는 두 실행이 같은 책갈피(여기서는 첫 실행이라 전체 읽기)를 읽어 원천 전체가 출력에
   * 두 번씩 들어갔다(둘 다 COMPLETED). 출력은 PK 없는 데이터셋이다 — PK 가 있으면 중복이 고유 위반 실패로 바뀌어 결함 모양이 달라진다. MERGE 가
   * 아니라 APPEND 인 이유도 같다(MERGE 는 멱등이라 중복을 가린다).
   *
   * <p>느린 실행은 {@code pg_sleep} 대신(SQL 가드가 막는다) 작은 두 계열의 곱 개수 세기로 만든다 — 메모리 안에서 끝나 디스크를 쓰지 않는다. 새 행은
   * 앞 실행의 본 문장이 원천을 읽는 중(원천 테이블의 관계 잠금을 쥔 동안)에 넣는다 — 그 전에 넣으면 앞 실행의 스냅샷에도 보여, 직렬 실행에서도 생기는 경계 겹침과
   * 구분이 안 된다.
   */
  @Test
  void 같은_증분_스텝의_실행이_겹쳐도_중복_적재하지_않고_새_행을_놓치지_않는다() throws Exception {
    seedSource(1, 20);
    List<DatasetColumnRequest> noPkColumns =
        List.of(
            new DatasetColumnRequest("code", "Code", "TEXT", null, true, false, null, false),
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null, false));
    String appendTable = "inc_app_" + suffix;
    Long appendDatasetId =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "Inc Append " + suffix,
                    appendTable,
                    null,
                    null,
                    "TABLE",
                    "DERIVED",
                    noPkColumns,
                    null),
                userId)
            .id();
    try {
      pipelineService.updatePipeline(
          pipelineId,
          new UpdatePipelineRequest(
              "Inc Pipeline " + suffix,
              "증분 처리 통합 테스트",
              null,
              List.of(
                  new PipelineStepRequest(
                      STEP_NAME,
                      "느린 증분 APPEND",
                      "SQL",
                      "SELECT code, name FROM "
                          + DataSchema.qualify(srcTable)
                          + " WHERE _updated_at >= {{last_run_at}}"
                          + " AND (SELECT count(*) FROM generate_series(1, 12000) a,"
                          + " generate_series(1, 12000) b) > 0",
                      appendDatasetId,
                      null,
                      null,
                      "APPEND"))),
          userId);

      Long exec1 = executionService.executePipeline(pipelineId, userId);
      awaitSourceReadInProgress();
      // 앞 실행의 본 문장이 원천을 읽는 중 — 이 행은 앞 실행에 보이지 않고, 뒤 실행만 읽어야 한다.
      dsl.execute(
          "INSERT INTO "
              + DataSchema.qualify(srcTable)
              + " (code, name) VALUES ('NEW1', 'new-row')");
      Long exec2 = executionService.executePipeline(pipelineId, userId);

      assertThat(awaitTerminal(exec1)).as("앞 실행").isEqualTo("COMPLETED");
      assertThat(awaitTerminal(exec2)).as("뒤 실행").isEqualTo("COMPLETED");
      // 전제: 두 실행이 실제로 겹쳤다(뒤 실행의 스텝이 앞 실행 스텝이 끝나기 전에 시작했다). 아니면 이 테스트는 직렬 실행을 본 것이라 공허하다.
      // 스텝 RUNNING 기록은 잠금 대기보다 앞이므로 수정 후에도 참이다. 실행 기록은 RLS 대상이라 테넌트 범위에서 읽는다.
      Boolean overlapped =
          inTenantFixture(
              () ->
                  dsl.fetchOne(
                          "SELECT (SELECT started_at FROM pipeline_step_execution WHERE execution_id = ?)"
                              + " < (SELECT completed_at FROM pipeline_step_execution"
                              + " WHERE execution_id = ?)",
                          exec2,
                          exec1)
                      .get(0, Boolean.class));
      assertThat(overlapped).as("두 실행이 겹쳐야 검증이 의미 있다").isTrue();

      var perCode =
          dsl.fetch(
              "SELECT code, count(*) AS n FROM "
                  + DataSchema.qualify(appendTable)
                  + " GROUP BY code HAVING count(*) > 1");
      assertThat(perCode).as("겹친 두 실행이 같은 구간을 두 번 적재하면 안 된다").isEmpty();
      assertThat(
              dsl.fetchOne("SELECT count(*) FROM " + DataSchema.qualify(appendTable))
                  .get(0, Integer.class))
          .as("원천 20행 + 앞 실행 도중 들어온 새 행 1개")
          .isEqualTo(21);
      assertThat(nameOf(appendTable, "NEW1"))
          .as("뒤 실행은 앞 실행이 전진시킨 책갈피로 읽어 새 행을 놓치지 않는다")
          .isEqualTo("new-row");
      assertThat(injectedLastRunAt(exec2)).as("뒤 실행은 앞 실행이 남긴 책갈피로 읽는다(전체 읽기 아님)").isNotNull();
    } finally {
      // 파이프라인이 이 데이터셋을 출력으로 참조하므로 먼저 지운다(tearDown 은 이미 지워진 파이프라인을 무시한다).
      try {
        pipelineService.deletePipelineUnchecked(pipelineId);
        pipelineId = null;
      } catch (Exception ignored) {
        // 정리 실패가 본 검증 결과를 가리지 않게 한다.
      }
      try {
        datasetService.deleteDataset(appendDatasetId);
      } catch (Exception ignored) {
        // 위와 같은 이유.
      }
    }
  }

  /**
   * 앞 실행의 본 문장(느린 계열 곱이 든 원천 읽기)이 실제로 실행 중일 때까지 기다린다.
   *
   * <p>원천 테이블 잠금(pg_locks)으로 보면 안 된다 — 관계 잠금은 트랜잭션 끝까지 남아, 같은 트랜잭션의 컬럼 probe(LIMIT 0)가 잡은 잠금도 "읽는
   * 중"으로 보인다. 전체 테스트처럼 부하가 크면 probe 와 본 문장 사이가 길어져 새 행이 본 문장 스냅샷보다 먼저 들어가 앞 실행에도 보였다(간헐 실패: NEW1 이
   * 두 번 적재). 그래서 본 문장이 active 이고 시작한 지 300ms 가 넘었는지를 본다(LIMIT 0 probe 는 곧 끝난다). READ COMMITTED 의 문장
   * 스냅샷은 실행 시작 때 잡히므로, 이때 넣은 행은 앞 실행에 보이지 않는다.
   *
   * <p>여기에 {@code backend_xmin IS NOT NULL} 도 함께 본다(WD-442) — query_start 는 파싱·계획 "전"에 찍히므로, 고부하로 그
   * 세션이 300ms 넘게 CPU 를 못 받으면 시간 조건만으로는 스냅샷 전일 수 있다. READ COMMITTED 에서 문장 사이에는 스냅샷이 풀려 xmin 이 비므로, 본
   * 문장이 active 인데 xmin 이 있으면 그 문장의 스냅샷이 이미 잡혔다는 뜻이다.
   *
   * <p>제품 쪽 판정(WD-442): 책갈피 후보는 SQL 실행 전에 {@code min(지금, 열린 트랜잭션 시작 시각)} 으로 잡고 다음 실행은 {@code >=} 로
   * 읽는 설계라(V123, PipelineAsyncRunner 증분 블록), 후보 시각과 본 문장 스냅샷 사이에 커밋된 행은 직렬 실행에서도 두 실행에 모두 보인다(MERGE
   * 가 흡수하는 의도된 at-least-once). 겹친 실행 직렬화(advisory 잠금)는 책갈피 읽기 전에 잡아 전진 뒤 풀어 결함이 없다. 그래서 새 행은 반드시 본
   * 문장 스냅샷 "뒤"에 넣어야 하고, 예전 간헐 실패(NEW1 두 번)는 이 관측이 너무 일렀던 탓이다.
   *
   * <p>다른 롤 세션의 상태·문장은 슈퍼유저만 볼 수 있어 테스트 풀(app_tenant)이 아니라 컨테이너 소유자 계정으로 따로 붙는다(SecurityEventsTest
   * 와 같은 방식).
   */
  private void awaitSourceReadInProgress() throws Exception {
    long deadline = System.currentTimeMillis() + 30_000;
    try (Connection watcher =
            DriverManager.getConnection(
                PostgresTestContainer.INSTANCE.getJdbcUrl(),
                PostgresTestContainer.INSTANCE.getUsername(),
                PostgresTestContainer.INSTANCE.getPassword());
        PreparedStatement ps =
            watcher.prepareStatement(
                "SELECT exists (SELECT 1 FROM pg_stat_activity WHERE datname = current_database()"
                    + " AND state = 'active' AND pid <> pg_backend_pid()"
                    + " AND query LIKE '%generate_series(1, 12000)%'"
                    + " AND query NOT ILIKE '%limit 0%'"
                    + " AND backend_xmin IS NOT NULL"
                    + " AND now() - query_start > interval '300 milliseconds')")) {
      while (System.currentTimeMillis() < deadline) {
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          if (rs.getBoolean(1)) {
            return;
          }
        }
        Thread.sleep(100);
      }
    }
    throw new IllegalStateException("앞 실행의 원천 읽기를 관측하지 못했습니다");
  }

  /** 실행이 종료 상태가 될 때까지 기다려 그 상태를 돌려준다. */
  private String awaitTerminal(Long executionId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 120_000;
    String status = null;
    while (System.currentTimeMillis() < deadline) {
      status = pipelineService.getExecutionById(pipelineId, executionId).status();
      if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
        return status;
      }
      Thread.sleep(200);
    }
    return status;
  }

  // ------------------------------------------------------------------ //
  // Helpers
  // ------------------------------------------------------------------ //

  /** 원천에 code=C{번호} 행을 채운다. {@code _updated_at} 은 트리거가 채운다. */
  private void seedSource(int from, int to) {
    dsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(srcTable)
            + " (code, name) SELECT 'C' || lpad(i::text, 4, '0'), 'name-' || lpad(i::text, 4, '0')"
            + " FROM generate_series(?, ?) AS i",
        from,
        to);
  }

  /** 파이프라인을 실행하고 종료 상태까지 기다린 뒤, 기대한 상태인지 확인하고 실행 ID 를 돌려준다. */
  private Long runAndWait(String expectedStatus) {
    Long executionId = executionService.executePipeline(pipelineId, userId);
    long deadline = System.currentTimeMillis() + 60_000;
    String status = null;
    while (System.currentTimeMillis() < deadline) {
      status = pipelineService.getExecutionById(pipelineId, executionId).status();
      if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
        break;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    assertThat(status).as("파이프라인 실행 %d 의 최종 상태", executionId).isEqualTo(expectedStatus);
    return executionId;
  }

  private Long stepId() {
    return stepRepository.findStepIdByPipelineAndName(pipelineId, STEP_NAME).orElseThrow();
  }

  private StepCursor cursor() {
    return stepRepository.findCursor(stepId()).orElseThrow();
  }

  /** 해당 실행의 스텝이 {@code {{last_run_at}}} 자리에 실제로 주입한 값(전체 읽기였으면 null). */
  private OffsetDateTime injectedLastRunAt(Long executionId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT injected_last_run_at FROM pipeline_step_execution"
                        + " WHERE execution_id = ?",
                    executionId)
                .get(0, OffsetDateTime.class));
  }

  /** 저장 검증을 거치지 않고 스텝 SQL 만 바꾼다 — 실행 시점 방어를 직접 확인하기 위한 레거시 행 흉내(WD-426). */
  private void replaceStepSql(String sql) {
    Long id = stepId();
    inTenantFixture(
        () -> {
          dsl.execute("UPDATE pipeline_step SET script_content = ? WHERE id = ?", sql, id);
        });
  }

  /** 해당 실행의 스텝 오류 문구. */
  private String stepErrorMessage(Long executionId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT error_message FROM pipeline_step_execution WHERE execution_id = ?",
                    executionId)
                .get(0, String.class));
  }

  private int outCount() {
    return dsl.fetchOne("SELECT count(*) FROM " + DataSchema.qualify(outTable))
        .get(0, Integer.class);
  }

  /** 행이 없으면 null. */
  private String nameOf(String table, String code) {
    var record =
        dsl.fetchOne("SELECT name FROM " + DataSchema.qualify(table) + " WHERE code = ?", code);
    return record == null ? null : record.get(0, String.class);
  }
}
