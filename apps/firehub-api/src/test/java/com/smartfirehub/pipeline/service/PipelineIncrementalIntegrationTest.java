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
 * <p>단위 테스트(목)는 "러너가 어떤 순서로 무엇을 부르는가"만 고정한다. 이 테스트는 그와 별개의 질문 —
 * "실제로 <b>변경된 행만</b> 다시 읽고, 출력은 합집합이 되는가" — 에 답한다. 그래서 검증 장치가
 * {@code count(*)} 가 아니라 <b>출력 행 변조(tamper) 탐침</b>이다: 2회차 전에 출력의 어떤 행을 손으로
 * 바꿔 두면, 그 행의 원천이 <b>변경되지 않았을 때</b>에만 변조가 살아남는다. 치환이 항상 {@code -infinity}
 * 를 내는 결함이면 그 행이 원천 값으로 되돌아가 이 단언이 깨진다(변이로 확인함) — 행 수 단언만으로는
 * MERGE 의 멱등성 때문에 그 결함이 영원히 보이지 않는다.
 *
 * <p><b>테스트 트랜잭션을 끈다({@code NOT_SUPPORTED}).</b> 파이프라인 실행은 {@code @Async} 라 별도
 * 스레드·커넥션에서 돈다 — 테스트 트랜잭션 안에서 픽스처를 만들면 그 스레드가 미커밋 행을 보지 못한다.
 * 만든 데이터셋·파이프라인은 {@link #tearDown()} 에서 명시적으로 지운다. 이름에 난수를 붙여 공유 test DB
 * 에서 다른 워크트리/테스트와 충돌하지 않게 한다.
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
        pipelineService.deletePipeline(pipelineId);
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
   * <p>Task 4 는 이 계약을 {@code SqlScriptExecutorSandboxTest}(실행기 단위)와
   * {@code PipelineAsyncRunnerTest}(목)로 나눠 고정했다. 여기서는 러너 → 실행기 → 실제 테이블까지
   * 한 번에 관통해 "비우기 DELETE 가 본 INSERT 와 같은 트랜잭션에 있다"를 최종 확인한다 — 증분과
   * 무관한 회귀 가드지만 같은 픽스처로 싸게 얻을 수 있다.
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

  // ------------------------------------------------------------------ //
  // 시나리오 4: 늦은 커밋 — candidate 규칙과 >= 경계
  // ------------------------------------------------------------------ //

  /**
   * 실행 <b>도중</b> 열려 있던 트랜잭션이 나중에 커밋한 행을 다음 실행이 반드시 읽어야 한다.
   *
   * <p>{@code _updated_at} 은 트랜잭션 <b>시작</b> 시각이므로, 미커밋 트랜잭션 T 가 있는 동안 잡은
   * 책갈피가 {@code T.xact_start} 보다 크면 T 가 커밋한 행은 영원히 사라진다. V123 의
   * {@code fh_incremental_cursor_candidate()} 가 {@code LEAST(clock, min(xact_start))} 를 돌려주어
   * 책갈피 = {@code T.xact_start} 가 되고, 사용자 SQL 의 {@code >=} 가 그 경계 행을 다시 읽는다 —
   * 즉 이 테스트 하나가 candidate 규칙과 {@code >=} 경계를 동시에 고정한다(별도 경계 행 테스트를 두지
   * 않는 이유: {@code UPDATE ... SET _updated_at} 은 트리거가 덮어써 경계를 인위적으로 만들 수 없다).
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
   * 코드리뷰 HIGH — {@code SELECT *} 스텝(출력 데이터셋 미지정 = 임시 데이터셋 자동 생성)이 실제로
   * 성공해야 한다.
   *
   * <p>V123 가 <b>모든</b> 데이터셋 물리 테이블에 {@code _updated_at} 을 추가했으므로 {@code SELECT *}
   * 결과에는 항상 그 컬럼이 섞여 들어온다. 예약어 별칭이 {@code _updated_at_1} 처럼 밑줄로 시작하면
   * {@code DataTableService.validateName}({@code ^[a-z][a-z0-9_]*$})이 거부해
   * {@code InvalidTableNameException} 으로 <b>100% 실패</b>한다. 별칭 헬퍼만 단위로 보면 "이름이
   * 바뀌었다"까지밖에 못 보므로, 여기서는 러너 → 임시 데이터셋 생성 → 적재까지 관통해 확인한다.
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
                  dsl
                      .fetch(
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
                      dsl.fetchValue("SELECT table_name FROM dataset WHERE id = ?", finalTempDatasetId));
      Integer loaded =
          dsl.fetchOne("SELECT count(*) FROM " + DataSchema.qualify(tempTable)).get(0, Integer.class);
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

  private int outCount() {
    return dsl.fetchOne("SELECT count(*) FROM " + DataSchema.qualify(outTable)).get(0, Integer.class);
  }

  /** 행이 없으면 null. */
  private String nameOf(String table, String code) {
    var record =
        dsl.fetchOne("SELECT name FROM " + DataSchema.qualify(table) + " WHERE code = ?", code);
    return record == null ? null : record.get(0, String.class);
  }
}
