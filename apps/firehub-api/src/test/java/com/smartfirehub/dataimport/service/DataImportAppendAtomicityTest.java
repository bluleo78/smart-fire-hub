package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataimport.dto.ImportResponse;
import com.smartfirehub.dataimport.exception.ImportProcessingException;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.repository.AsyncJobRepository;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * WD-386: 기본 키가 있는 데이터셋에 APPEND 로 임포트할 때, 뒤쪽 배치에서 키가 겹치면(파일 안 중복·기존 행과 충돌) 앞 배치만 커밋되어 남던 부분 적재 결함의
 * 회귀 테스트. APPEND 는 삽입 스트림 전체를 하나의 트랜잭션으로 감싸므로 어느 배치에서 실패해도 전부 롤백되어 대상 테이블 행 수가 그대로여야 하고, 잡은 「중복
 * 데이터가 존재합니다…」 로 실패해야 한다. 그 긴 트랜잭션 중에도 진행률은 독립 커밋되어 다른 트랜잭션에서 보이는지, 중복 키 안내가 데이터 행 쓰기에서 난 충돌(PG
 * SQLState·Tibero DuplicateKeyException)에만 붙고 관리용 인덱스 충돌에는 붙지 않는지도 확인한다.
 *
 * <p>트랜잭션 없이 돈다 — 기본 키 충돌 INSERT 가 테스트 트랜잭션 안에서 나면 트랜잭션이 abort 되어 이후 잡 실패 처리·감사 기록·staging 정리까지
 * 25P02 로 함께 깨진다(DataImportPhysicalLimitIntegrationTest 와 같은 이유). 그래서 고유 이름으로 만들고 {@link #tearDown}
 * 에서 직접 정리한다. 스파이 조합도 그 클래스와 같게 두어 Spring 컨텍스트를 공유한다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DataImportAppendAtomicityTest extends IntegrationTestBase {

  /** 배치 경계를 넘는 파일을 만들 때 기준 — 서비스 값을 그대로 쓴다. */
  private static final int BATCH_SIZE = DataImportService.BATCH_SIZE;

  private static final String DUPLICATE_MESSAGE = "중복 데이터가 존재합니다";

  @Autowired private DataImportService dataImportService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;
  @Autowired private AsyncJobRepository asyncJobRepository;

  @MockitoSpyBean private AsyncJobService asyncJobService;

  private Long userId;
  private final List<Long> datasetIds = new ArrayList<>();
  private final List<Path> tempFiles = new ArrayList<>();
  private final List<String> createdJobIds = new ArrayList<>();

  @BeforeEach
  void setUpFixtures() {
    userId = TenantRlsTestSupport.insertUser(dsl, "wd386_user_");
  }

  @AfterEach
  void tearDown() throws Exception {
    for (Path p : tempFiles) {
      Files.deleteIfExists(p);
    }
    // 임포트가 감사 로그를 남기므로 사용자 정리(TestUsers.cleanup)는 마지막이다(FK).
    List<Runnable> cleanups = new ArrayList<>();
    for (String jobId : createdJobIds) {
      cleanups.add(
          () ->
              TenantRlsTestSupport.runInTenantTransaction(
                  fixtureTransactionTemplate,
                  DEFAULT_TEST_TENANT_ID,
                  () -> dsl.execute("delete from async_job where id = ?", jobId)));
    }
    for (Long id : datasetIds) {
      cleanups.add(() -> datasetService.deleteDataset(id));
    }
    cleanups.add(
        () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID));
    TenantRlsTestSupport.cleanupAll(cleanups.toArray(Runnable[]::new));
  }

  // ------------------------------------------------------------------ //
  // 실패 — 한 행도 남지 않는다
  // ------------------------------------------------------------------ //

  @Test
  void 파일_안_중복_키가_뒤쪽_배치에_있으면_한_행도_적재되지_않고_중복_안내로_실패한다() throws Exception {
    // given: 기본 키 데이터셋, 2배치 + 1행(4001행). 3001번째 행이 1번째 행과 같은 키 — 예전엔 첫 배치(2000행)가 커밋되어 남았다.
    String table = newTable();
    Long datasetId = createPkDataset(table);
    int rows = BATCH_SIZE * 2 + 1;
    Path csv = writeCsv(rows, 3001, "K1");

    // when
    runImport("wd386-infile-dup", datasetId, csv);

    // then: 대상 행 수 0, 잡은 한국어 중복 안내로 실패, 임포트 이력도 실패
    assertThat(countRows(table)).isZero();
    String message = capturedFailMessage("wd386-infile-dup");
    assertThat(message).isEqualTo(DataImportService.DUPLICATE_KEY_MESSAGE);
    assertThat(message).contains(DUPLICATE_MESSAGE).contains("파일 안에").contains("업서트(UPSERT)");
    ImportResponse history = dataImportService.getImportsByDatasetId(datasetId).get(0);
    assertThat(history.status()).isEqualTo("FAILED");
  }

  @Test
  void 기존_행과_충돌하는_키가_뒤쪽_배치에_있으면_기존_행_수가_그대로다() throws Exception {
    // given: 기존 행 1건(K3500) — 그 뒤 5000행 파일의 3500번째(두 번째 배치)가 같은 키라 충돌한다.
    String table = newTable();
    Long datasetId = createPkDataset(table);
    runImport("wd386-seed", datasetId, writeRawCsv("code,name\nK3500,기존\n"));
    assertThat(countRows(table)).isEqualTo(1);
    Mockito.clearInvocations(asyncJobService);

    // when
    runImport("wd386-existing-dup", datasetId, writeCsv(5000, -1, null));

    // then: 새 행 0건 — 기존 1건만 남고 그 값도 바뀌지 않는다.
    assertThat(countRows(table)).isEqualTo(1);
    assertThat(
            dsl.fetchValue(
                "SELECT name FROM " + DataSchema.qualify(table) + " WHERE code = 'K3500'"))
        .isEqualTo("기존");
    assertThat(capturedFailMessage("wd386-existing-dup")).contains(DUPLICATE_MESSAGE);
  }

  // ------------------------------------------------------------------ //
  // 성공 — 기존 동작 유지
  // ------------------------------------------------------------------ //

  @Test
  void 기본_키_데이터셋에_여러_배치_APPEND_는_모든_행을_파일_순서대로_적재한다() throws Exception {
    // given: 기존 행 1건(X0) + 배치 경계를 넘는 겹치지 않는 5000행
    String table = newTable();
    Long datasetId = createPkDataset(table);
    runImport("wd386-seed-ok", datasetId, writeRawCsv("code,name\nX0,기존\n"));

    // when
    runImport("wd386-ok", datasetId, writeCsv(5000, -1, null));

    // then: 5001행, id 가 파일 순서대로 매겨진다(배치 순서 보존)
    assertThat(countRows(table)).isEqualTo(5001);
    Mockito.verify(asyncJobService, Mockito.never())
        .failJob(Mockito.eq("wd386-ok"), Mockito.anyString());
    List<String> firstCodes =
        dsl.fetch(
                "SELECT code FROM "
                    + DataSchema.qualify(table)
                    + " WHERE code <> 'X0' ORDER BY id LIMIT 3")
            .getValues(0, String.class);
    assertThat(firstCodes).containsExactly("K1", "K2", "K3");
  }

  @Test
  void 기본_키_없는_데이터셋_APPEND_는_같은_값이_반복돼도_모두_적재한다() throws Exception {
    // given: 기본 키 없는 데이터셋 — 중복이 오류가 아니다. 배치 경계를 넘는 4001행 중 3001번째가 1번째와 같다.
    String table = newTable();
    Long datasetId = createDataset(table, false);

    // when
    runImport("wd386-nopk", datasetId, writeCsv(BATCH_SIZE * 2 + 1, 3001, "K1"));

    // then
    assertThat(countRows(table)).isEqualTo(BATCH_SIZE * 2 + 1);
    Mockito.verify(asyncJobService, Mockito.never())
        .failJob(Mockito.eq("wd386-nopk"), Mockito.anyString());
  }

  // ------------------------------------------------------------------ //
  // 진행률 — 긴 삽입 트랜잭션과 독립 커밋
  // ------------------------------------------------------------------ //

  @Test
  void APPEND_삽입_중_진행률은_삽입_트랜잭션_커밋_전에_다른_트랜잭션에서_보인다() throws Exception {
    // given: 실제 async_job 행(커밋됨)과 배치 경계를 넘는 파일. INSERTING 진행률이 쓰인 직후, 아직 삽입 트랜잭션 안에서
    // 다른 스레드(= 다른 커넥션·트랜잭션)로 async_job 단계를 읽는다. 바깥 트랜잭션에 합류했다면 커밋 전이라 이전 단계가 보인다.
    String table = newTable();
    Long datasetId = createPkDataset(table);
    String jobId =
        asyncJobService.createJob("IMPORT", "dataset", String.valueOf(datasetId), userId, Map.of());
    createdJobIds.add(jobId);
    List<String> stagesSeenElsewhere = new CopyOnWriteArrayList<>();
    List<Boolean> insideImportTx = new CopyOnWriteArrayList<>();
    ExecutorService otherThread = Executors.newSingleThreadExecutor();
    try {
      Mockito.doAnswer(
              inv -> {
                inv.callRealMethod();
                insideImportTx.add(TransactionSynchronizationManager.isActualTransactionActive());
                stagesSeenElsewhere.add(
                    otherThread
                        .submit(
                            () -> {
                              TenantContext.set(DEFAULT_TEST_TENANT_ID);
                              try {
                                return asyncJobRepository
                                    .findById(jobId)
                                    .map(AsyncJobStatusResponse::stage)
                                    .orElse("NONE");
                              } finally {
                                TenantContext.clear();
                              }
                            })
                        .get(10, TimeUnit.SECONDS));
                return null;
              })
          .when(asyncJobService)
          .updateProgressInNewTransaction(
              Mockito.eq(jobId),
              Mockito.eq("INSERTING"),
              Mockito.anyInt(),
              Mockito.anyString(),
              Mockito.any());

      // when
      runImport(jobId, datasetId, writeCsv(BATCH_SIZE * 2 + 1, -1, null));
    } finally {
      otherThread.shutdownNow();
    }

    // then: 진행률 갱신은 삽입 트랜잭션 안에서 불렸고, 그때 이미 다른 트랜잭션에서 INSERTING 이 보였다. 임포트는 성공.
    assertThat(insideImportTx).isNotEmpty().containsOnly(true);
    assertThat(stagesSeenElsewhere).isNotEmpty().containsOnly("INSERTING");
    Mockito.verify(asyncJobService, Mockito.never())
        .failJob(Mockito.eq(jobId), Mockito.anyString());
    assertThat(countRows(table)).isEqualTo(BATCH_SIZE * 2 + 1);
  }

  // ------------------------------------------------------------------ //
  // 중복 키 안내 번역 — 데이터 행 쓰기 지점에서만
  // ------------------------------------------------------------------ //

  @Test
  void 데이터_행_쓰기의_DuplicateKeyException_은_감싸여_있어도_중복_안내로_번역된다() {
    // given: Tibero 저장소처럼 Spring DuplicateKeyException 으로 번역된 예외가 다른 예외에 감싸여 올라온 경우
    RuntimeException wrapped =
        new RuntimeException(
            "batch failed", new DuplicateKeyException("JDBC-10007:UNIQUE constraint violation"));

    // when / then
    assertThatThrownBy(
            () ->
                DataImportService.dataWrite(
                    () -> {
                      throw wrapped;
                    }))
        .isInstanceOf(ImportProcessingException.class)
        .hasMessage(DataImportService.DUPLICATE_KEY_MESSAGE)
        .hasCause(wrapped);
  }

  @Test
  void 데이터_행_쓰기의_SQLState_23505_는_메시지와_무관하게_중복_안내로_번역된다() {
    // given: PostgreSQL 고유 위반 — 메시지 문구가 아니라 SQLState 로 판정한다
    RuntimeException wrapped =
        new RuntimeException("jOOQ", new SQLException("whatever text", "23505"));

    assertThatThrownBy(
            () ->
                DataImportService.dataWrite(
                    () -> {
                      throw wrapped;
                    }))
        .isInstanceOf(ImportProcessingException.class)
        .hasMessage(DataImportService.DUPLICATE_KEY_MESSAGE);
  }

  @Test
  void 데이터_행_쓰기의_다른_오류는_그대로_던진다() {
    RuntimeException other = new RuntimeException("x", new SQLException("bad", "22001"));

    assertThatThrownBy(
            () ->
                DataImportService.dataWrite(
                    () -> {
                      throw other;
                    }))
        .isSameAs(other);
  }

  @Test
  void 데이터_행_쓰기_밖의_DuplicateKeyException_은_데이터_중복으로_안내하지_않는다() {
    // given: 임포트 이력 같은 관리용 고유 인덱스 충돌 — 데이터 행 쓰기 지점을 거치지 않았다
    Exception bookkeeping =
        new RuntimeException(
            "outer",
            new DuplicateKeyException(
                "duplicate key value violates unique constraint \"data_import_pkey\""));

    assertThat(dataImportService.toUserFriendlyMessage(bookkeeping))
        .isNotEqualTo(DataImportService.DUPLICATE_KEY_MESSAGE)
        .doesNotContain("중복 데이터");
  }

  @Test
  void 진행_중_임포트_충돌은_제약_이름으로만_판정한다() {
    // given: 제약 이름이 idx_async_job_active_unique 인 PostgreSQL 서버 오류(원인 체인 안쪽)
    Exception byConstraint =
        new RuntimeException("outer", psqlUniqueViolation("idx_async_job_active_unique", "x"));
    // given: 메시지에만 그 이름이 들어 있고 제약은 다른 경우(사용자 값이 메시지에 실린 경우 등)
    Exception byTextOnly =
        new RuntimeException(
            "outer",
            psqlUniqueViolation("data_table_pkey", "Key (code)=(idx_async_job_active_unique)"));

    assertThat(dataImportService.toUserFriendlyMessage(byConstraint)).contains("이미 진행 중인 임포트");
    assertThat(dataImportService.toUserFriendlyMessage(byTextOnly)).doesNotContain("이미 진행 중인 임포트");
  }

  @Test
  void 원인_체인이_순환해도_탐색이_끝난다() {
    // given: a → b → a 순환 체인
    RuntimeException a = new RuntimeException("a");
    RuntimeException b = new RuntimeException("b", a);
    a.initCause(b);

    assertThat(DataImportService.findInChain(a, t -> t instanceof IllegalStateException)).isEmpty();
    assertThat(dataImportService.toUserFriendlyMessage(a)).isNotBlank();
  }

  // ------------------------------------------------------------------ //
  // helpers
  // ------------------------------------------------------------------ //

  private String newTable() {
    return "wd386_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private Long createPkDataset(String table) {
    return createDataset(table, true);
  }

  /** code(VARCHAR(20)) + name(TEXT). withPk 면 code 가 기본 키다. */
  private Long createDataset(String table, boolean withPk) {
    Long id =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "WD386 Dataset " + table,
                    table,
                    "APPEND 전부-아니면-전무 회귀 테스트",
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest(
                            "code", "코드", "VARCHAR", 20, !withPk, false, null, withPk),
                        new DatasetColumnRequest(
                            "name", "이름", "TEXT", null, true, false, null, false)),
                    null),
                userId)
            .id();
    datasetIds.add(id);
    return id;
  }

  /** K1..Kn 행을 만든다. dupRow(1-based) 가 양수면 그 행의 코드를 dupCode 로 바꿔 키를 겹치게 한다. */
  private Path writeCsv(int rows, int dupRow, String dupCode) throws Exception {
    StringBuilder csv = new StringBuilder("code,name\n");
    for (int i = 1; i <= rows; i++) {
      String code = (i == dupRow) ? dupCode : "K" + i;
      csv.append(code).append(",이름").append(i).append("\n");
    }
    return writeRawCsv(csv.toString());
  }

  private Path writeRawCsv(String content) throws Exception {
    Path dir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-test");
    Files.createDirectories(dir);
    Path p = Files.createTempFile(dir, "wd386-", ".csv");
    Files.writeString(p, content, StandardCharsets.UTF_8);
    tempFiles.add(p);
    return p;
  }

  private void runImport(String jobId, Long datasetId, Path csv) throws Exception {
    dataImportService.processImport(
        jobId,
        datasetId,
        csv.toString(),
        "",
        "",
        csv.getFileName().toString(),
        Files.size(csv),
        "CSV",
        userId,
        "WD386 User",
        "",
        "",
        "APPEND",
        DEFAULT_TEST_TENANT_ID);
  }

  /** 고유 위반 PSQLException — 서버 오류 필드(S·C·M·n=제약 이름)를 직접 채운다. */
  private static PSQLException psqlUniqueViolation(String constraint, String detail) {
    return new PSQLException(
        new ServerErrorMessage(
            "SERROR\0C23505\0Mduplicate key value violates unique constraint\0D"
                + detail
                + "\0n"
                + constraint
                + "\0"));
  }

  private String capturedFailMessage(String jobId) {
    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    Mockito.verify(asyncJobService).failJob(Mockito.eq(jobId), msg.capture());
    return msg.getValue();
  }

  private long countRows(String table) {
    return dsl.fetchCount(DSL.table(DataSchema.qualify(table)));
  }
}
