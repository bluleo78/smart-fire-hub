package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.AddColumnRequest;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.exception.DatasetInUseException;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.dataset.storage.DatasetStructureLock;
import com.smartfirehub.global.datastore.DataStoreDb;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * WD-670 — 실행기를 끈 파이프라인 SQL 스텝(테넌트 파이프라인 풀, 플랫폼 행 잠금 없음)과 데이터셋 구조 변경의 직렬화를 실제 PG 에서 끝에서 끝까지 확인한다.
 *
 * <p><b>수정 전에는 실패한다(근거).</b> 수정 전 SQL 스텝은 데이터 저장소 트랜잭션에서 어떤 구조 잠금도 잡지 않았다. (a)·(b) 처럼 스텝이 쓰기 도중 멈춰
 * 있으면 필드 추가·삭제·데이터셋 삭제는 409 없이 DDL(ACCESS EXCLUSIVE)에서 스텝을 기다리며 멈추므로(관문을 열 때까지 끝나지 않는다) 「수 초 안에
 * 409」 단언이 실패한다. (c) 는 스텝이 구조 변경 트랜잭션의 테이블 잠금을 기다렸다가 사라진 컬럼에 INSERT 해 DB 원문(「column "name" ... does
 * not exist」)으로 실패하므로 「구조가 바뀌었습니다」 문구 단언이 실패한다.
 *
 * <p>테스트 트랜잭션은 끈다 — 실행은 {@code @Async} 라 별도 스레드·커넥션에서 돌고, 픽스처가 커밋돼 있어야 보인다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineSqlStepStructureLockIntegrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired @DataStoreDb private DSLContext dataDsl;

  /** 관문 연결용 데이터 저장소 DataSource — SQL 스텝의 잠금은 데이터 저장소에서 잡히므로 관문·관측도 같은 DB 여야 한다. */
  @Autowired @DataStoreDb private DataSource dataSource;

  @Autowired private DatasetService datasetService;
  @Autowired private DatasetColumnRepository columnRepository;
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
    srcTable = "p670_src_" + suffix;
    outTable = "p670_out_" + suffix;
    userId =
        inTenantFixture(
            () ->
                dsl.insertInto(USER)
                    .set(USER.USERNAME, "p670_user_" + suffix)
                    .set(USER.PASSWORD, "password")
                    .set(USER.NAME, "P670 Test User")
                    .set(USER.EMAIL, "p670_" + suffix + "@example.com")
                    .returning(USER.ID)
                    .fetchOne()
                    .getId());
    inTenantFixture(
        () -> TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID));
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID, "USER");

    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("code", "Code", "TEXT", null, true, false, null, false),
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null, false));
    srcDatasetId = createDataset("Src", srcTable, "SOURCE", columns);
    outDatasetId = createDataset("Out", outTable, "DERIVED", columns);
    dataDsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(srcTable)
            + " (code, name) VALUES ('a','A'), ('b','B')");
  }

  @AfterEach
  void tearDown() {
    for (Long pipelineId : pipelineIds) {
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
          // 위와 같은 이유(이미 지운 데이터셋 포함).
        }
      }
    }
    if (userId != null) {
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
   * (a) REPLACE SELECT 스텝이 출력 잠금(#731)에서 멈춘 동안(구조 공유 잠금은 이미 쥠) 필드 삭제는 수 초 안에 409 로 끝나고, 관문을 열면 스텝이
   * 끝까지 완료되며 필드 목록과 물리 컬럼이 일치한다.
   */
  @Test
  void REPLACE_SELECT_스텝이_쓰는_중이면_필드_삭제는_409이고_스텝은_완료된다() throws Exception {
    Long pipelineId =
        createPipeline(
            "replace", "SELECT code, name FROM " + DataSchema.qualify(srcTable), outDatasetId);
    String outputLockKey =
        TenantContext.runScopedGet(
            DEFAULT_TEST_TENANT_ID,
            () -> SqlScriptExecutor.outputLockKey(OutputClearStatement.deleteAll(outTable)));
    Long nameColumnId = columnId(outDatasetId, "name");

    Long executionId;
    try (Connection gate = dataSource.getConnection()) {
      gate.setAutoCommit(false);
      try (PreparedStatement lock =
              gate.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))");
          Statement st = gate.createStatement()) {
        lock.setString(1, outputLockKey);
        lock.execute();
        executionId = executionService.executePipeline(pipelineId, userId);
        assertThat(awaitWaiters(st, "locktype = 'advisory'"))
            .as("스텝이 출력 잠금을 기다려야 한다")
            .isGreaterThanOrEqualTo(1);

        long started = System.nanoTime();
        Throwable failure =
            runWithin(() -> datasetService.deleteColumn(outDatasetId, nameColumnId), 20);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(failure)
            .isInstanceOf(DatasetInUseException.class)
            .hasMessage(DatasetStructureLock.BUSY_MESSAGE);
        assertThat(elapsedMs).isBetween(4_000L, 15_000L);
      } finally {
        gate.rollback();
      }
    }

    assertThat(waitForEnd(executionId)).isEqualTo("COMPLETED");
    assertThat(outCodes()).containsExactly("a", "b");
    assertThat(physicalUserColumns(outTable)).containsExactlyInAnyOrder("code", "name");
    assertThat(metaColumns(outDatasetId)).containsExactlyInAnyOrder("code", "name");
  }

  /**
   * (a') APPEND SELECT 스텝이 출력 INSERT 에서 멈춘 동안 NOT NULL 필드 추가는 수 초 안에 409 로 끝나고, 스텝은 완료되며 필드 목록과 물리
   * 컬럼이 일치한다(NOT NULL 필드가 빈 값으로 끼어들지 않는다).
   */
  @Test
  void APPEND_SELECT_스텝이_쓰는_중이면_NOT_NULL_필드_추가는_409이다() throws Exception {
    Long pipelineId =
        createPipeline(
            "append",
            "SELECT code, name FROM " + DataSchema.qualify(srcTable),
            outDatasetId,
            "APPEND");

    Long executionId;
    try (Connection gate = dataSource.getConnection()) {
      gate.setAutoCommit(false);
      try (Statement st = gate.createStatement()) {
        // 출력 INSERT(ROW EXCLUSIVE)만 막는다 — 원천 probe·구조 확인(카탈로그)·행 수 세기(ACCESS SHARE)는 막지 않는다.
        st.execute("LOCK TABLE " + DataSchema.qualify(outTable) + " IN SHARE MODE");
        executionId = executionService.executePipeline(pipelineId, userId);
        assertThat(awaitWaiters(st, "locktype = 'relation'")).isGreaterThanOrEqualTo(1);

        Throwable failure =
            runWithin(
                () ->
                    datasetService.addColumn(
                        outDatasetId,
                        new AddColumnRequest(
                            "must", "Must", "TEXT", null, false, false, null, false)),
                20);
        assertThat(failure)
            .isInstanceOf(DatasetInUseException.class)
            .hasMessage(DatasetStructureLock.BUSY_MESSAGE);
      } finally {
        gate.rollback();
      }
    }

    assertThat(waitForEnd(executionId)).isEqualTo("COMPLETED");
    assertThat(outCodes()).containsExactly("a", "b");
    assertThat(physicalUserColumns(outTable)).containsExactlyInAnyOrder("code", "name");
    assertThat(metaColumns(outDatasetId)).containsExactlyInAnyOrder("code", "name");
  }

  /**
   * (b) 출력 없이 다른 데이터셋에 쓰는 DML 스텝이 쓰는 동안 그 데이터셋 삭제는 수 초 안에 409 로 끝난다(데이터셋은 남는다). 출력이 없어 데이터셋 행 잠금·출력
   * 잠금 어느 것도 잡지 않던 경로다 — 쓰기 대상은 보안 관문이 판정한 DML 쓰기 대상에서 온다.
   */
  @Test
  void 출력_없는_DML_스텝이_쓰는_중이면_대상_데이터셋_삭제는_409이다() throws Exception {
    Long pipelineId =
        createPipeline(
            "dml", "UPDATE " + DataSchema.qualify(srcTable) + " SET name = 'Z'", null, "APPEND");

    Long executionId;
    try (Connection gate = dataSource.getConnection()) {
      gate.setAutoCommit(false);
      try (Statement st = gate.createStatement()) {
        // 행 잠금으로 UPDATE 만 멈춘다(테이블 잠금이 아니라 probe·계획에는 영향이 없다).
        st.execute("SELECT * FROM " + DataSchema.qualify(srcTable) + " FOR UPDATE");
        executionId = executionService.executePipeline(pipelineId, userId);
        assertThat(awaitWaiters(st, "locktype IN ('transactionid', 'tuple')"))
            .isGreaterThanOrEqualTo(1);

        Throwable failure = runWithin(() -> datasetService.deleteDataset(srcDatasetId), 20);
        assertThat(failure)
            .isInstanceOf(DatasetInUseException.class)
            .hasMessage(DatasetStructureLock.BUSY_MESSAGE);
      } finally {
        gate.rollback();
      }
    }

    assertThat(waitForEnd(executionId)).isEqualTo("COMPLETED");
    assertThat(
            dataDsl
                .fetch("SELECT DISTINCT name FROM " + DataSchema.qualify(srcTable))
                .getValues(0, String.class))
        .containsExactly("Z");
  }

  /**
   * (c) 구조 변경이 진행 중(배타 구조 잠금 + 필드 삭제 DDL 미커밋)일 때 시작한 스텝은 공유 잠금에서 기다렸다가, 변경이 커밋된 뒤 계획 컬럼이 사라진 것을 확인해
   * 아무것도 쓰지 않고 깔끔한 문구로 실패한다(이전 출력 유지).
   */
  @Test
  void 기다리는_동안_출력_필드가_지워지면_깔끔한_문구로_실패하고_출력은_그대로다() throws Exception {
    dataDsl.execute(
        "INSERT INTO " + DataSchema.qualify(outTable) + " (code, name) VALUES ('old','O')");
    Long pipelineId =
        createPipeline(
            "changed", "SELECT code, name FROM " + DataSchema.qualify(srcTable), outDatasetId);
    String structureKey =
        TenantContext.runScopedGet(
            DEFAULT_TEST_TENANT_ID, () -> DatasetStructureLock.keyFor(outTable));

    Long executionId;
    try (Connection gate = dataSource.getConnection()) {
      gate.setAutoCommit(false);
      try (PreparedStatement lock =
              gate.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))");
          Statement st = gate.createStatement()) {
        // 구조 변경 트랜잭션을 흉내 낸다 — 배타 구조 잠금을 쥐고 필드를 지운 채 아직 커밋하지 않는다.
        lock.setString(1, structureKey);
        lock.execute();
        st.execute("ALTER TABLE " + DataSchema.qualify(outTable) + " DROP COLUMN name");
        executionId = executionService.executePipeline(pipelineId, userId);
        assertThat(awaitWaiters(st, "true")).isGreaterThanOrEqualTo(1);
        gate.commit();
      } catch (Exception e) {
        gate.rollback();
        throw e;
      }
    }

    assertThat(waitForEnd(executionId)).isEqualTo("FAILED");
    String error =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "SELECT error_message FROM pipeline_step_execution WHERE execution_id = ?",
                        executionId)
                    .get(0, String.class));
    assertThat(error).contains("구조가 바뀌었습니다").contains("name").doesNotContain("does not exist");
    assertThat(outCodes()).containsExactly("old");
  }

  // ------------------------------------------------------------------ //
  // Helpers
  // ------------------------------------------------------------------ //

  private Long createDataset(
      String label, String table, String origin, List<DatasetColumnRequest> columns) {
    return datasetService
        .createDataset(
            new CreateDatasetRequest(
                "P670 " + label + " " + suffix, table, null, null, "TABLE", origin, columns, null),
            userId)
        .id();
  }

  private Long createPipeline(String label, String sql, Long outputDatasetId) {
    return createPipeline(label, sql, outputDatasetId, "REPLACE");
  }

  private Long createPipeline(String label, String sql, Long outputDatasetId, String strategy) {
    Long pipelineId =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest(
                    "P670 " + label + " " + suffix,
                    "구조 잠금 테스트",
                    List.of(
                        new PipelineStepRequest(
                            "step", null, "SQL", sql, outputDatasetId, null, null, strategy))),
                userId)
            .id();
    pipelineIds.add(pipelineId);
    return pipelineId;
  }

  private Long columnId(Long datasetId, String name) {
    return columnRepository.findByDatasetId(datasetId).stream()
        .filter(c -> c.columnName().equals(name))
        .findFirst()
        .orElseThrow()
        .id();
  }

  /**
   * 본문을 다른 스레드(테넌트 문맥 포함)에서 돌려 {@code seconds} 안에 끝나면 던진 예외(없으면 null)를 돌려준다. 시간 안에 끝나지 않으면 「멈춤」으로
   * 실패시킨다 — 수정 전 동작(DDL 이 쓰기를 기다리며 멈춤)이 여기서 드러난다. 멈춘 본문은 관문을 열면 풀린다.
   */
  private Throwable runWithin(Runnable body, int seconds) throws Exception {
    CompletableFuture<Void> f =
        CompletableFuture.runAsync(
            () -> TenantContext.runScoped(DEFAULT_TEST_TENANT_ID, body::run));
    try {
      f.get(seconds, TimeUnit.SECONDS);
      return null;
    } catch (ExecutionException e) {
      return e.getCause();
    } catch (TimeoutException e) {
      throw new AssertionError("구조 변경이 " + seconds + "초 안에 끝나지 않았다(쓰기를 기다리며 멈춤)", e);
    }
  }

  /** 조건에 맞는 미획득 잠금(이 연결 제외)이 생길 때까지(최대 30초) 기다렸다가 그 수를 돌려준다. */
  private int awaitWaiters(Statement st, String condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    int waiting = 0;
    while (System.nanoTime() < deadline) {
      try (ResultSet rs =
          st.executeQuery(
              "SELECT count(DISTINCT pid) FROM pg_locks WHERE NOT granted AND pid <>"
                  + " pg_backend_pid() AND "
                  + condition)) {
        rs.next();
        waiting = rs.getInt(1);
      }
      if (waiting >= 1) {
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
    return dataDsl
        .fetch("SELECT code FROM " + DataSchema.qualify(outTable) + " ORDER BY code")
        .getValues(0, String.class);
  }

  /** 물리 테이블의 사용자 컬럼(시스템 컬럼 제외). */
  private List<String> physicalUserColumns(String table) {
    return dataDsl
        .fetch(
            "SELECT attname::text FROM pg_attribute WHERE attrelid = to_regclass(?) AND attnum > 0"
                + " AND NOT attisdropped AND attname NOT IN ('id','import_id','created_at','_updated_at')",
            TenantContext.runScopedGet(DEFAULT_TEST_TENANT_ID, () -> DataSchema.qualify(table)))
        .getValues(0, String.class);
  }

  private List<String> metaColumns(Long datasetId) {
    return columnRepository.findByDatasetId(datasetId).stream().map(c -> c.columnName()).toList();
  }
}
