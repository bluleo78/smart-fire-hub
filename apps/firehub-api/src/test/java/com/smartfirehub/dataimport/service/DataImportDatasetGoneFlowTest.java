package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * WD-675 — 가져오기 잡이 대상 테이블에 쓰기 직전 데이터 쓰기 잠금을 잡을 때 데이터셋이 이미 지워졌으면, 잠금 층의 {@code onGone} 계약대로 「임포트 중
 * 데이터셋이 삭제되어…」 안내로 실패하고 아무것도 쓰지 않는지 <b>실제 잠금 구현</b>({@code DatasetDataWriteLock})으로 서비스 흐름을 돌며
 * 확인한다.
 *
 * <p>왜: 단위 테스트용 통과 잠금(PassThroughWriteLock)은 모든 데이터셋이 남아 있다고 보고 {@code onGone} 을 부르지 않으므로, 서비스가 변환을
 * 잘못 넘기거나 빠뜨려도 그쪽 테스트는 모른다. 이 테스트가 서비스 배선({@code writeToTarget} → {@code ON_DATASET_GONE})을 지킨다.
 *
 * <p>재현: 잡은 데이터셋을 읽은 뒤 검증(Pass1)을 하고 그다음 삽입(Pass2)에서 잠근다. 검증 배치의 진행률 갱신 시점에 별도 스레드·별도 트랜잭션으로 데이터셋을
 * <b>실제로</b> 삭제(커밋)해, 삽입 직전 잠금에서 데이터셋이 없게 만든다. 스파이 조합은 다른 가져오기 테스트(DataImportAppendAtomicityTest
 * 등)와 같게 {@link AsyncJobService} 하나만 두어 Spring 컨텍스트를 새로 만들지 않고 공유한다.
 *
 * <p>트랜잭션 없이 돈다 — 삭제가 별도 트랜잭션에서 커밋돼야 잡이 그것을 보므로. 그래서 고유 이름으로 만들고 {@link #tearDown} 에서 데이터셋·데이터
 * 테이블·사용자를 직접 정리한다(분리 데이터 저장소 모드에서도 남지 않게).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DataImportDatasetGoneFlowTest extends IntegrationTestBase {

  @Autowired private DataImportService dataImportService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  /** 데이터 저장소(data 스키마) 확인·정리용 — 분리 모드에서는 플랫폼 DB 와 다른 DB 다(WD-494). */
  @Autowired @DataStoreDb private DSLContext dataDsl;

  /** 진행률 갱신 시점을 삭제 계기로 쓰고 실패 사유를 잡는 스파이 — 다른 가져오기 테스트와 같은 조합(컨텍스트 공유). */
  @MockitoSpyBean private AsyncJobService asyncJobService;

  private Long userId;
  private final List<Long> datasetIds = new ArrayList<>();
  private final List<String> tables = new ArrayList<>();
  private final List<Path> tempFiles = new ArrayList<>();

  @BeforeEach
  void setUpFixtures() {
    userId = TenantRlsTestSupport.insertUser(dsl, "wd675_user_");
  }

  @AfterEach
  void tearDown() throws Exception {
    for (Path p : tempFiles) {
      Files.deleteIfExists(p);
    }
    // 테스트가 삭제하지 못하고 끝났을 때를 위해 남은 데이터셋·데이터 테이블을 지운다. 임포트가 감사 로그를 남기므로 사용자 정리는 마지막이다(FK).
    List<Runnable> cleanups = new ArrayList<>();
    for (Long id : datasetIds) {
      cleanups.add(
          () -> {
            if (datasetExists(id)) {
              datasetService.deleteDataset(id);
            }
          });
    }
    for (String table : tables) {
      cleanups.add(() -> dataDsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(table)));
    }
    cleanups.add(
        () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID));
    TenantRlsTestSupport.cleanupAll(cleanups.toArray(Runnable[]::new));
  }

  @Test
  void 삽입_직전_잠금에서_데이터셋이_지워졌으면_삭제_안내로_실패하고_적재하지_않는다() throws Exception {
    String table = "wd675_gone_" + uniqueSuffix();
    Long datasetId = createDataset(table);
    String jobId = "wd675-gone-" + UUID.randomUUID();

    // 검증 배치의 진행률 갱신(삽입 단계 직전) 때 다른 트랜잭션으로 데이터셋을 실제로 지운다 — 한 번만
    AtomicBoolean deleted = new AtomicBoolean();
    ExecutorService other = Executors.newSingleThreadExecutor();
    try {
      Mockito.doAnswer(
              inv -> {
                Map<String, Object> metadata = inv.getArgument(4);
                if (!metadata.containsKey("totalRows") && deleted.compareAndSet(false, true)) {
                  other
                      .submit(
                          () ->
                              TenantRlsTestSupport.runInTenantTransaction(
                                  fixtureTransactionTemplate,
                                  DEFAULT_TEST_TENANT_ID,
                                  () -> datasetService.deleteDataset(datasetId)))
                      .get(30, TimeUnit.SECONDS);
                }
                return inv.callRealMethod();
              })
          .when(asyncJobService)
          .updateProgress(
              Mockito.eq(jobId),
              Mockito.eq("VALIDATING"),
              Mockito.anyInt(),
              Mockito.anyString(),
              Mockito.anyMap());

      Path csv = writeCsv("name\nA\nB\n");
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
          "WD675 User",
          "",
          "",
          "APPEND",
          DEFAULT_TEST_TENANT_ID);
    } finally {
      other.shutdownNow();
    }

    assertThat(deleted).as("검증 단계 중에 데이터셋을 지웠다").isTrue();
    // 잠금 층이 onGone 으로 만든 안내가 잡 실패 사유로 그대로 보인다(원문 「테이블 없음」·NPE 가 아니라)
    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    Mockito.verify(asyncJobService).failJob(Mockito.eq(jobId), msg.capture());
    assertThat(msg.getValue()).isEqualTo(DataImportService.DATASET_GONE_MESSAGE);
    // 데이터셋·데이터 테이블은 삭제대로 사라졌고, 그 뒤에 다시 만들어 쓰지 않았다
    assertThat(datasetExists(datasetId)).isFalse();
    assertThat(dataDsl.fetchValue("SELECT to_regclass(?) IS NULL", DataSchema.qualify(table)))
        .isEqualTo(true);
  }

  /** 테이블 이름용 고유 접미사 — 테이블 이름 규칙({@code [a-z][a-z0-9_]*})에 맞게 UUID 의 16진 12자를 쓴다. */
  private static String uniqueSuffix() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  /** name(TEXT) 하나짜리 테이블형 데이터셋 — 고유 이름으로 만들고 정리 목록에 올린다. */
  private Long createDataset(String table) {
    tables.add(table);
    Long id =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "WD675 가져오기 삭제됨 " + table,
                    table,
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("name", "이름", "TEXT", null, true, false, null)),
                    null),
                userId)
            .id();
    datasetIds.add(id);
    return id;
  }

  private Path writeCsv(String content) throws Exception {
    Path dir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-test");
    Files.createDirectories(dir);
    Path p = Files.createTempFile(dir, "wd675-", ".csv");
    Files.writeString(p, content, StandardCharsets.UTF_8);
    tempFiles.add(p);
    return p;
  }

  /** 데이터셋 행이 남아 있는지(테넌트 픽스처 트랜잭션에서 읽는다). */
  private boolean datasetExists(Long datasetId) {
    return inTenantFixture(() -> TenantRlsTestSupport.rowExists(dsl, "dataset", "id", datasetId));
  }
}
