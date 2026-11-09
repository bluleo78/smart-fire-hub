package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.dataset.exception.OutputStructureChangedException;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetDataWriteLock;
import com.smartfirehub.dataset.service.DatasetService;
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
 * WD-670 — 페이지마다 커밋하는 API_CALL APPEND 적재에서 페이지 사이에 필드가 지워지면, 다음 페이지가 DB 원문(「column "name" ... does
 * not exist」) 대신 그 필드 이름을 짚은 깔끔한 문구로 실패한다. 이미 커밋된 앞 페이지 행은 남는다(APPEND 의 페이지 커밋 특성 그대로).
 *
 * <p><b>수정 전에는 실패한다(근거).</b> 수정 전 페이지 쓰기는 KEY SHARE 를 잡은 뒤 현재 필드 목록을 보지 않고 바로 INSERT 해, 지워진 필드에 대한
 * jOOQ {@code BadSqlGrammarException}(42703 원문)이 그대로 올라왔다 — 아래 예외 타입·문구 단언이 실패한다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApiCallExecutorPageFieldDeletedTest extends IntegrationTestBase {

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
    table = "wd670_app_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
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
  void 페이지_사이에_필드가_지워지면_그_필드를_짚어_깔끔하게_실패한다() throws Exception {
    CompletableFuture<Throwable> load = startAppend();
    // 둘째 페이지 요청이 왔다 = 첫 페이지가 이미 커밋됐다(페이지 사이에는 잠금을 쥐지 않는다).
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

    assertThat(failure)
        .isInstanceOf(OutputStructureChangedException.class)
        .hasMessageContaining("구조가 바뀌었습니다")
        .hasMessageContaining("'name'");
    assertThat(failure.getMessage()).doesNotContain("does not exist");
    // 첫 페이지(n1·n2)는 이미 커밋됐다 — 둘째 페이지는 아무것도 쓰지 않았다.
    assertThat(codes()).containsExactly("n1", "n2", "old");
  }

  /** APPEND 적재를 다른 스레드(테넌트 문맥 포함)에서 시작하고, 끝나면 던진 예외(성공이면 null)를 돌려준다. */
  private CompletableFuture<Throwable> startAppend() {
    String baseUrl = "http://localhost:" + wireMock.port();
    return CompletableFuture.supplyAsync(
        () ->
            TenantContext.runScopedGet(
                DEFAULT_TEST_TENANT_ID,
                () -> {
                  try {
                    executor.execute(
                        ApiCallLoadDriftTestSupport.config(baseUrl), table, null, "APPEND", null);
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
}
