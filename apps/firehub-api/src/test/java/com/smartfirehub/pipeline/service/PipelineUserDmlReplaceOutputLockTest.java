package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자가 직접 쓴 DML(비SELECT) SQL 스텝 + REPLACE 가 같은 출력에 겹칠 때의 직렬화(#735) — 실제 DB 에서 파이프라인 실행 두 건(그리고
 * SELECT 자동 적재 실행과의 조합)을 끝에서 끝까지 겹쳐 확인한다.
 *
 * <p><b>왜 {@code SqlScriptExecutorOutputLockTest}(#731)로는 부족한가.</b> 그 테스트는 "선행 문장이 넘어왔을 때" 실행기가 잠금을
 * 잡는지만 본다. #735 의 결함은 그 앞 단계 — 러너가 사용자 DML 스텝에는 선행 문장을 아예 만들지 않고 즉시 커밋되는 truncate 를 따로 하던 것 — 이라,
 * 러너를 거치는 실제 파이프라인 실행으로만 드러난다.
 *
 * <p>테스트 프로파일의 테넌트 파이프라인 풀 크기(1)로는 두 실행이 같은 커넥션을 차례로 써 겹침이 만들어지지 않으므로 운영 기본값(2)으로 올린다. 테스트 트랜잭션은 끈다
 * — 실행은 {@code @Async} 라 별도 스레드·커넥션에서 돌고, 픽스처가 커밋돼 있어야 그쪽에서 보인다.
 */
@TestPropertySource(properties = "app.pipeline.tenant-pool.max-size=2")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineUserDmlReplaceOutputLockTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  /** 관문 잠금 전용 커넥션을 빌리기 위한 메인 풀. */
  @Autowired private DataSource dataSource;

  @Autowired private DatasetService datasetService;
  @Autowired private PipelineService pipelineService;
  @Autowired private PipelineExecutionService executionService;

  private String suffix;
  private String srcTable;
  private String outTable;
  private Long srcDatasetId;
  private Long outDatasetId;
  private Long userId;
  private final List<Long> pipelineIds = new ArrayList<>();

  @BeforeEach
  void setUpFixtures() {
    suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    srcTable = "p735_src_" + suffix;
    outTable = "p735_out_" + suffix;

    userId =
        inTenantFixture(
            () ->
                dsl.insertInto(USER)
                    .set(USER.USERNAME, "p735_user_" + suffix)
                    .set(USER.PASSWORD, "password")
                    .set(USER.NAME, "P735 Test User")
                    .set(USER.EMAIL, "p735_" + suffix + "@example.com")
                    .returning(USER.ID)
                    .fetchOne()
                    .getId());

    // 보안 등급(S2): 파이프라인 SQL 스텝은 저장·실행 시 사용자 자격으로 판정된다 — ACTIVE 멤버십 + USER 역할(기본 '내부' 자격)이
    // 있어야 기본 등급('내부') 데이터셋을 볼 수 있다.
    inTenantFixture(
        () -> TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID));
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID, "USER");

    // 출력에는 PK 를 두지 않는다 — PK 가 있으면 중복이 유니크 위반(실행 실패)으로 바뀌어, 이슈가 말하는
    // "둘 다 완료인데 행이 두 배" 증상을 그대로 관측할 수 없다.
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("code", "Code", "TEXT", null, true, false, null, false),
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null, false));
    srcDatasetId =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "P735 Src " + suffix, srcTable, null, null, "TABLE", "SOURCE", columns, null),
                userId)
            .id();
    outDatasetId =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "P735 Out " + suffix, outTable, null, null, "TABLE", "DERIVED", columns, null),
                userId)
            .id();

    // 원천 2행, 출력에는 이전 실행 결과 1행.
    dsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(srcTable)
            + " (code, name) VALUES ('a','A'), ('b','B')");
    dsl.execute("INSERT INTO " + DataSchema.qualify(outTable) + " (code, name) VALUES ('old','O')");
  }

  @AfterEach
  void tearDown() {
    for (Long pipelineId : pipelineIds) {
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
            dsl.execute("DELETE FROM user_role WHERE user_id = ?", userId);
            TenantRlsTestSupport.deleteMembership(dsl, userId);
            dsl.deleteFrom(USER).where(USER.ID.eq(userId)).execute();
          });
    }
  }

  /**
   * 사용자가 직접 쓴 INSERT(REPLACE) 스텝의 같은 파이프라인을 두 번 겹쳐 실행해도 출력에는 한 번 분량만 남는다(#735).
   *
   * <p><b>수정 전에는 실패한다(실측 — 4행).</b> 수정 전 러너는 출력을 즉시 커밋되는 truncate 로 따로 비웠다. 두 실행이 각자 비우기를 끝낸 뒤 각자
   * INSERT 하므로 두 실행분이 모두 남는다.
   */
  @Test
  void 사용자_INSERT_REPLACE_스텝을_겹쳐_실행해도_행이_중복되지_않는다() throws Exception {
    Long pipelineId = createPipeline("dml", userInsertSql());

    List<Long> executions = runOverlapped(pipelineId, pipelineId);

    for (Long executionId : executions) {
      assertThat(waitForEnd(executionId)).as("실행 %d 의 최종 상태", executionId).isEqualTo("COMPLETED");
    }
    assertThat(outCodes()).containsExactly("a", "b");
  }

  /**
   * 사용자 INSERT(REPLACE) 실행과 SELECT 자동 적재(REPLACE, #731) 실행은 <b>같은 잠금 키</b>를 쓴다 — 서로 배타라는 것의 직접 증거다.
   *
   * <p>테스트 커넥션이 그 키(SQL 경로가 쓰는 것과 같은 식)의 advisory 잠금을 쥔 동안 두 파이프라인을 실행하면 <b>둘 다</b> advisory 잠금 대기에
   * 들어가야 한다. 수정 전에는 사용자 INSERT 실행이 잠금을 잡지 않고 그대로 끝나므로 대기가 1건뿐이라 실패한다(실측). 원천 관문 방식을 쓰지 않는 이유:
   * SELECT 실행은 컬럼 probe 단계에서 원천 관문에 먼저 걸려, 잠금이 없어도 우연히 순차로 끝난다.
   */
  @Test
  void 사용자_INSERT_REPLACE와_SELECT_REPLACE는_같은_출력_잠금_키에서_서로_기다린다() throws Exception {
    Long dmlPipeline = createPipeline("dml", userInsertSql());
    Long selectPipeline =
        createPipeline("sel", "SELECT code, name FROM " + DataSchema.qualify(srcTable));
    String key =
        TenantContext.runScopedGet(
            DEFAULT_TEST_TENANT_ID,
            () -> SqlScriptExecutor.outputLockKey(OutputClearStatement.deleteAll(outTable)));

    List<Long> executions = new ArrayList<>();
    try (Connection gate = dataSource.getConnection()) {
      gate.setAutoCommit(false);
      try (PreparedStatement lock =
              gate.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))");
          Statement st = gate.createStatement()) {
        lock.setString(1, key);
        lock.execute();
        executions.add(executionService.executePipeline(dmlPipeline, userId));
        executions.add(executionService.executePipeline(selectPipeline, userId));

        int waiting =
            awaitWaiters(
                st,
                "SELECT count(DISTINCT pid) FROM pg_locks"
                    + " WHERE locktype = 'advisory' AND NOT granted");
        assertThat(waiting).as("두 실행 모두 같은 키의 advisory 잠금을 기다려야 한다").isEqualTo(2);
        // 잠금을 쥔 동안에는 어느 실행도 출력을 건드리지 못했다(비우기가 잠금 뒤에 있다).
        assertThat(outCodes()).containsExactly("old");
      } finally {
        gate.rollback();
      }
    }

    for (Long executionId : executions) {
      assertThat(waitForEnd(executionId)).as("실행 %d 의 최종 상태", executionId).isEqualTo("COMPLETED");
    }
    assertThat(outCodes()).containsExactly("a", "b");
  }

  /**
   * 사용자 DML 이 실패하면 비우기도 함께 롤백되어 이전 출력이 그대로 남는다 — 비우기가 사용자 DML 과 같은 트랜잭션이 됐다는 것의 직접 증거다(수정 전에는
   * truncate 가 먼저 커밋돼 출력이 빈 채로 남았다).
   */
  @Test
  void 사용자_DML이_실패하면_비우기도_롤백되어_이전_출력이_남는다() throws Exception {
    Long pipelineId =
        createPipeline(
            "fail",
            "INSERT INTO "
                + DataSchema.qualify(outTable)
                + " (code, name) SELECT code, (1 / (length(code) - 1))::text FROM "
                + DataSchema.qualify(srcTable));

    Long executionId = executionService.executePipeline(pipelineId, userId);

    assertThat(waitForEnd(executionId)).isEqualTo("FAILED");
    assertThat(outCodes()).containsExactly("old");
  }

  // ------------------------------------------------------------------ //
  // Helpers
  // ------------------------------------------------------------------ //

  /**
   * 사용자가 직접 쓴 INSERT. <b>원천을 CTE 로 먼저 읽는 형태여야 한다</b> — PostgreSQL 은 구문 분석 때 관계 잠금을 등장 순서대로 잡는데,
   * {@code INSERT INTO 출력 SELECT FROM 원천} 꼴이면 출력의 ROW EXCLUSIVE 를 먼저 쥔 채 원천 관문에서 멈춘다. 그러면 수정 전
   * 코드에서도 뒤 실행의 TRUNCATE(ACCESS EXCLUSIVE)가 그 잠금에 막혀 저절로 직렬화되므로, 결함이 있어도 통과하는 공허한 테스트가 된다. WITH 절은
   * 대상 테이블보다 먼저 분석되므로 출력 잠금 없이 원천 관문에서 멈춘다.
   */
  private String userInsertSql() {
    return "WITH s AS (SELECT code, name FROM "
        + DataSchema.qualify(srcTable)
        + ") INSERT INTO "
        + DataSchema.qualify(outTable)
        + " (code, name) SELECT code, name FROM s";
  }

  private Long createPipeline(String label, String sql) {
    Long pipelineId =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest(
                    "P735 " + label + " " + suffix,
                    "사용자 DML REPLACE 직렬화 테스트",
                    List.of(
                        new PipelineStepRequest(
                            "step", null, "SQL", sql, outDatasetId, null, null, "REPLACE"))),
                userId)
            .id();
    pipelineIds.add(pipelineId);
    return pipelineId;
  }

  /**
   * 두 파이프라인 실행을 <b>결정적으로</b> 겹친다 — 타이밍(sleep)이 아니라 제3 커넥션의 관문으로. 테스트 커넥션이 원천 테이블에 ACCESS EXCLUSIVE
   * 를 쥔 동안 두 실행을 띄우고, 두 실행의 백엔드가 모두 잠금 대기에 들어간 것을 {@code pg_locks} 로 확인한 뒤에 관문을 연다(#731 테스트와 같은
   * 방식).
   */
  private List<Long> runOverlapped(Long firstPipeline, Long secondPipeline) throws Exception {
    List<Long> executions = new ArrayList<>();
    // 관문은 풀에서 직접 빌린 전용 커넥션으로 쥔다 — 테스트 스레드의 Spring 트랜잭션에 묶으면
    // executePipeline 이 만드는 실행 레코드까지 그 트랜잭션에 합류해, 비동기 러너가 보지 못한다.
    try (Connection gate = dataSource.getConnection()) {
      gate.setAutoCommit(false);
      try (Statement st = gate.createStatement()) {
        st.execute("LOCK TABLE " + DataSchema.qualify(srcTable) + " IN ACCESS EXCLUSIVE MODE");
        executions.add(executionService.executePipeline(firstPipeline, userId));
        executions.add(executionService.executePipeline(secondPipeline, userId));

        // pg_locks 만 본다(pg_stat_activity 는 트랜잭션 안에서 첫 조회 스냅샷으로 고정된다 — #731
        // 테스트의 실측). 테스트 DB 는 이 JVM 전용 컨테이너라 다른 세션의 잠금 대기가 섞이지 않는다.
        int waiting =
            awaitWaiters(
                st,
                "SELECT count(DISTINCT pid) FROM pg_locks"
                    + " WHERE NOT granted AND pid <> pg_backend_pid()");
        // 겹침이 실제로 만들어졌는지 단언한다 — 없으면 두 실행이 우연히 순차로 돌아 공허해진다.
        assertThat(waiting).as("두 실행이 모두 잠금 대기 중이어야 겹침이 성립한다").isEqualTo(2);
      } finally {
        // 관문을 연다(단언 실패 시에도 — 안 그러면 두 실행이 영영 멈춘 채 정리 단계가 막힌다).
        gate.rollback();
      }
    }
    return executions;
  }

  /** 잠금 대기 중인 백엔드 수가 2가 될 때까지(최대 30초) 기다렸다가 마지막 관측값을 돌려준다. */
  private int awaitWaiters(Statement st, String countSql) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    int waiting = 0;
    while (System.nanoTime() < deadline) {
      try (ResultSet rs = st.executeQuery(countSql)) {
        rs.next();
        waiting = rs.getInt(1);
      }
      if (waiting >= 2) {
        break;
      }
      Thread.sleep(50);
    }
    return waiting;
  }

  private String waitForEnd(Long executionId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 60_000;
    String status = null;
    while (System.currentTimeMillis() < deadline) {
      // pipeline_execution 은 RLS 테이블이라 테넌트 픽스처 트랜잭션 안에서 읽는다.
      status =
          inTenantFixture(
              () ->
                  dsl.fetchOne("SELECT status FROM pipeline_execution WHERE id = ?", executionId)
                      .get(0, String.class));
      if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
        break;
      }
      Thread.sleep(100);
    }
    return status;
  }

  private List<String> outCodes() {
    return dsl.fetch("SELECT code FROM " + DataSchema.qualify(outTable) + " ORDER BY code")
        .getValues(0, String.class);
  }
}
