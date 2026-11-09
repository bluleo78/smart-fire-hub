package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.dataset.dto.AddColumnRequest;
import com.smartfirehub.dataset.exception.OutputStructureChangedException;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetDataWriteLock;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.dataset.storage.DataTableNames;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * WD-670 — REPLACE 적재가 스테이징(임시 테이블)에 쓰는 동안 출력 데이터셋에 필드가 추가·삭제되면, 맞바꿈이 그 변경을 조용히 지우지 않고 깔끔한 문구로 실패하며
 * 원본(행·필드)을 그대로 둔다.
 *
 * <p><b>수정 전에는 실패한다(근거).</b> 스테이징은 {@code createTempTable} 때 원본 구조를 복사하고, 스테이징 적재 동안에는 원본에 아무 잠금도
 * 없어 필드 변경이 커밋된다. 수정 전 맞바꿈은 구조를 비교하지 않고 원본을 지운 뒤 옛 구조의 스테이징을 원본 이름으로 바꿨다 — 실행은 성공하고(예외 없음 → 아래 단언
 * 실패), {@code dataset_column} 에는 새 필드가 있는데 물리 테이블에는 없는 어긋남이 남았다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineReplaceStagingDriftTest extends IntegrationTestBase {

  static WireMockServer wireMock;

  @BeforeAll
  static void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @Autowired private DatasetService datasetService;
  @Autowired private DatasetColumnRepository columnRepository;
  @Autowired private DataTableService dataTableService;
  @Autowired private DataTableRowService dataTableRowService;
  @Autowired private DatasetDataWriteLock writeLock;
  @Autowired @DataStoreDb private DSLContext dataDsl;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  private String table;
  private Long datasetId;
  private ApiCallExecutor executor;

  @BeforeEach
  void setUp() {
    wireMock.resetAll();
    table = "wd670_rep_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    // 데이터셋 작성자(NOT NULL FK) — 롤백이 없는 테스트라 이름이 겹치지 않게 시각을 붙인다.
    String suffix = Long.toString(System.nanoTime());
    Long userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                encoder,
                "wd670_" + suffix,
                "wd670_" + suffix + "@example.com",
                "Password123",
                "WD670",
                DEFAULT_TEST_TENANT_ID)
            .id();
    datasetId = ApiCallLoadDriftTestSupport.createDataset(datasetService, table, userId);
    dataDsl.execute(
        "INSERT INTO " + DataSchema.qualify(table) + " (code, name) VALUES ('old','O')");
    executor =
        ApiCallLoadDriftTestSupport.executor(dataTableService, dataTableRowService, writeLock);
    ApiCallLoadDriftTestSupport.stubTwoPages(wireMock, 2_000);
  }

  @AfterEach
  void tearDown() {
    try {
      datasetService.deleteDataset(datasetId);
    } catch (Exception ignored) {
      // 정리 실패가 본 검증 결과를 가리지 않게 한다.
    }
  }

  @Test
  void 스테이징_중_필드가_추가되면_맞바꾸지_않고_원본과_새_필드를_지킨다() throws Exception {
    CompletableFuture<Throwable> load = startReplace();
    ApiCallLoadDriftTestSupport.awaitSecondPageRequested(wireMock);
    TenantContext.runScoped(
        DEFAULT_TEST_TENANT_ID,
        () ->
            datasetService.addColumn(
                datasetId,
                new AddColumnRequest("extra", "Extra", "TEXT", null, true, false, null, false)));

    Throwable failure = load.get(30, TimeUnit.SECONDS);

    assertThat(failure)
        .isInstanceOf(OutputStructureChangedException.class)
        .hasMessageContaining("구조가 바뀌었습니다");
    assertThat(codes()).containsExactly("old");
    assertThat(physicalUserColumns()).containsExactlyInAnyOrder("code", "name", "extra");
    assertThat(metaColumns()).containsExactlyInAnyOrder("code", "name", "extra");
    assertThat(leftoverTempTables()).isZero();
  }

  @Test
  void 스테이징_중_필드가_삭제되면_맞바꾸지_않고_원본을_지킨다() throws Exception {
    CompletableFuture<Throwable> load = startReplace();
    ApiCallLoadDriftTestSupport.awaitSecondPageRequested(wireMock);
    Long nameId =
        columnRepository.findByDatasetId(datasetId).stream()
            .filter(c -> c.columnName().equals("name"))
            .findFirst()
            .orElseThrow()
            .id();
    TenantContext.runScoped(
        DEFAULT_TEST_TENANT_ID, () -> datasetService.deleteColumn(datasetId, nameId));

    Throwable failure = load.get(30, TimeUnit.SECONDS);

    assertThat(failure).isInstanceOf(OutputStructureChangedException.class);
    assertThat(codes()).containsExactly("old");
    assertThat(physicalUserColumns()).containsExactlyInAnyOrder("code");
    assertThat(metaColumns()).containsExactlyInAnyOrder("code");
    assertThat(leftoverTempTables()).isZero();
  }

  /** REPLACE 적재를 다른 스레드(테넌트 문맥 포함)에서 시작하고, 끝나면 던진 예외(성공이면 null)를 돌려준다. */
  private CompletableFuture<Throwable> startReplace() {
    String baseUrl = "http://localhost:" + wireMock.port();
    return CompletableFuture.supplyAsync(
        () ->
            TenantContext.runScopedGet(
                DEFAULT_TEST_TENANT_ID,
                () -> {
                  try {
                    executor.execute(
                        ApiCallLoadDriftTestSupport.config(baseUrl), table, null, "REPLACE", null);
                    return null;
                  } catch (Throwable t) {
                    return t;
                  }
                }));
  }

  private List<String> codes() {
    return dataDsl
        .fetch("SELECT code FROM " + DataSchema.qualify(table) + " ORDER BY code")
        .getValues(0, String.class);
  }

  private List<String> physicalUserColumns() {
    return dataDsl
        .fetch(
            "SELECT attname::text FROM pg_attribute WHERE attrelid = to_regclass(?) AND attnum > 0"
                + " AND NOT attisdropped AND attname NOT IN ('id','import_id','created_at','_updated_at')",
            DataSchema.qualify(table))
        .getValues(0, String.class);
  }

  private List<String> metaColumns() {
    return columnRepository.findByDatasetId(datasetId).stream().map(c -> c.columnName()).toList();
  }

  /** 이 출력의 임시 테이블(rtmp_…)이 남았는지 — 실패 정리가 스테이징을 버렸어야 한다. */
  private int leftoverTempTables() {
    return dataDsl
        .fetchOne(
            "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = ? AND c.relkind = 'r' AND c.relname LIKE ?",
            DataSchema.current(),
            DataTableService.TEMP_TABLE_PREFIX + "%_" + DataTableNames.md5Hex(table))
        .get(0, Integer.class);
  }
}
