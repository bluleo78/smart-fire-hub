package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataimport.dto.ValidationErrorDetail;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * WD-102: 임포트 잡이 실패(FAILED)로 끝나면 끝난 경로와 무관하게 「임포트 실패」 알림이 <b>정확히 한 번</b> 가야 한다.
 *
 * <p>WD-96 fail-fast 전환 이후 검증 실패·기본 키 빈 값은 예외 없이 failJob → return 으로 끝나서, catch 경로만 보내던 실패 알림이 빠져
 * 있었다. 알림 횟수와 문구(한국어 제목·설명)는 SseEmitterRegistry#broadcast 로 실제 넘어간 이벤트를 잡아 확인한다(연결이 없어도 broadcast
 * 호출 자체는 기록된다).
 *
 * <p>스파이 조합이 DataImportServiceTest(AsyncJobService·DataValidationService)와 달라 Spring 컨텍스트를 따로 쓴다.
 * 감사 로그 저장 예외에서도 알림이 한 번뿐인지 보려면 AuditLogService 스파이가 꼭 필요하고, 이 스파이를 쓰는 다른 테스트 클래스가 없어 어느 쪽으로 나눠도
 * 컨텍스트 하나는 더 생긴다. 그래서 알림 검증을 이 클래스 한 곳에 모은다.
 */
@Transactional
class DataImportFailureNotificationTest extends IntegrationTestBase {

  /** 검증 실패(fail-fast return 경로)가 남기는 잡 실패 사유의 앞부분. */
  private static final String VALIDATION_FAIL_PREFIX = "검증 실패로 한 행도 적재되지 않았습니다";

  @Autowired private DataImportService dataImportService;

  @Autowired private DatasetService datasetService;

  @Autowired private DSLContext dsl;

  @MockitoSpyBean private AsyncJobService asyncJobService;

  @MockitoSpyBean private SseEmitterRegistry sseEmitterRegistry;

  @MockitoSpyBean private DataValidationService validationService;

  @MockitoSpyBean private AuditLogService auditLogService;

  private Long testUserId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(com.smartfirehub.jooq.Tables.USER)
            .set(com.smartfirehub.jooq.Tables.USER.USERNAME, "import_notify_user")
            .set(com.smartfirehub.jooq.Tables.USER.PASSWORD, "password")
            .set(com.smartfirehub.jooq.Tables.USER.NAME, "Import Notify User")
            .set(com.smartfirehub.jooq.Tables.USER.EMAIL, "import_notify@example.com")
            .returning(com.smartfirehub.jooq.Tables.USER.ID)
            .fetchOne()
            .getId();
  }

  /** code(VARCHAR(5), 기본 키) + name(TEXT) 데이터셋. 글자 수 초과·기본 키 빈 값·UPSERT 를 한 스키마로 재현한다. */
  private Long createDataset(String tableName, String displayName) {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("code", "Code", "VARCHAR", 5, false, false, null, true),
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null, false));
    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                displayName,
                tableName,
                "WD-102 실패 알림 테스트용",
                null,
                "TABLE",
                "SOURCE",
                columns,
                null),
            testUserId);
    return dataset.id();
  }

  private Path writeCsv(String content) throws Exception {
    Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-test");
    Files.createDirectories(tempDir);
    Path tempFile = Files.createTempFile(tempDir, "import-notify-", ".csv");
    Files.writeString(tempFile, content, StandardCharsets.UTF_8);
    return tempFile;
  }

  private void runImport(String jobId, Long datasetId, String filePath, String mode)
      throws Exception {
    dataImportService.processImport(
        jobId,
        datasetId,
        filePath,
        "",
        "",
        "notify.csv",
        100L,
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        mode,
        1L);
  }

  /** broadcast 로 실제 나간 임포트 알림 이벤트만 추린다(데이터셋 생성 등 다른 알림은 제외). */
  private List<NotificationEvent> broadcastImportEvents() {
    ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
    Mockito.verify(sseEmitterRegistry, Mockito.atLeast(0))
        .broadcast(Mockito.eq(testUserId), captor.capture());
    return captor.getAllValues().stream().filter(e -> e.eventType().startsWith("IMPORT_")).toList();
  }

  /**
   * 임포트 알림이 실패 알림 하나뿐(성공 알림 없음)이고, 그 문구가 한국어 「임포트 실패」 / 「'X' 데이터셋 임포트에 실패했습니다.」 이며 해당 데이터셋을 가리키는지
   * 확인한다.
   */
  private void assertExactlyOneFailureNotification(Long datasetId, String datasetName) {
    List<NotificationEvent> events = broadcastImportEvents();
    assertThat(events).hasSize(1);
    NotificationEvent event = events.get(0);
    assertThat(event.eventType()).isEqualTo("IMPORT_FAILED");
    assertThat(event.entityId()).isEqualTo(datasetId);
    assertThat(event.title()).isEqualTo("임포트 실패");
    assertThat(event.description()).isEqualTo("'" + datasetName + "' 데이터셋 임포트에 실패했습니다.");
  }

  /**
   * 잡이 정확히 한 번 FAILED 로 끝났고 그 사유가 failMessagePrefix 로 시작하는지(= 어느 실패 경로로 끝났는지) 확인한 뒤, 실패 알림이 정확히 한
   * 번인지 본다.
   */
  private void assertFailedOnceWithNotification(
      String jobId, String failMessagePrefix, Long datasetId, String datasetName) {
    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    Mockito.verify(asyncJobService, Mockito.times(1)).failJob(Mockito.eq(jobId), msg.capture());
    assertThat(msg.getValue()).startsWith(failMessagePrefix);
    assertExactlyOneFailureNotification(datasetId, datasetName);
  }

  @Test
  void validationFailure_lengthExceeded_sendsFailureNotificationOnce() throws Exception {
    // given: code 는 VARCHAR(5) — 10글자 값은 검증(Pass1)에서 글자 수 초과로 걸린다(fail-fast return 경로)
    String name = "알림 글자수 초과";
    Long datasetId = createDataset("notify_len_dataset", name);
    Path csv = writeCsv("code,name\nA001,ok\nABCDEFGHIJ,too long\n");

    runImport("notify-len-job", datasetId, csv.toString(), "APPEND");

    assertFailedOnceWithNotification("notify-len-job", VALIDATION_FAIL_PREFIX, datasetId, name);
  }

  @Test
  void upsert_emptyPkCell_sendsFailureNotificationOnce() throws Exception {
    // given: UPSERT 에서 기본 키 칸이 빈 행. 기본 키는 NULL 불가라 일반 검증(필수값 누락)이 먼저 잡는다 —
    // 사용자가 실제로 "기본 키 빈 값" 으로 겪는 실패는 이 검증 실패 return 경로다.
    String name = "알림 PK 빈 값";
    Long datasetId = createDataset("notify_emptypk_dataset", name);
    Path csv = writeCsv("code,name\nA001,ok\n,no key\n");

    runImport("notify-emptypk-job", datasetId, csv.toString(), "UPSERT");

    assertFailedOnceWithNotification("notify-emptypk-job", VALIDATION_FAIL_PREFIX, datasetId, name);
  }

  @Test
  void upsert_pkValidationFailure_sendsFailureNotificationOnce() throws Exception {
    // given: Pass2 의 기본 키 검사(validatePrimaryKeys)가 빈 값을 보고하는 경로. 공개 API 로는 일반 검증이 먼저
    // 걸러 도달하기 어려우므로(방어 코드) 검사 결과만 스텁해 이 return 경로 자체를 고정한다.
    String name = "알림 PK 검사 실패";
    Long datasetId = createDataset("notify_pkcheck_dataset", name);
    Path csv = writeCsv("code,name\nA001,ok\n");
    Mockito.doReturn(
            new DataValidationService.PkValidationResult(
                List.of(new ValidationErrorDetail(1, "code", "", "기본키 컬럼은 비어 있을 수 없습니다")),
                List.of()))
        .when(validationService)
        .validatePrimaryKeys(Mockito.anyList(), Mockito.anyList());

    runImport("notify-pkcheck-job", datasetId, csv.toString(), "UPSERT");

    assertFailedOnceWithNotification(
        "notify-pkcheck-job",
        "Primary key validation failed: NULL values in PK columns",
        datasetId,
        name);
  }

  @Test
  void validationFailure_auditLogThrows_stillNotifiesOnlyOnce() throws Exception {
    // given: 검증 실패 경로에서 감사 로그 저장이 한 번 실패하면 catch 경로로 떨어진다. 이때 return 경로의 알림과
    // catch 경로의 알림이 겹쳐 두 번 가면 안 된다. return 경로는 감사 로그 뒤에서만 failImport 를 부르므로 잡 실패
    // 처리와 알림 모두 catch 의 1번만 나가야 하고, 사유는 catch 가 만든 사용자용 메시지다.
    String name = "알림 감사로그 실패";
    Long datasetId = createDataset("notify_auditfail_dataset", name);
    Path csv = writeCsv("code,name\nABCDEFGHIJ,too long\n");
    Mockito.doThrow(new RuntimeException("audit down"))
        .doCallRealMethod()
        .when(auditLogService)
        .log(
            Mockito.any(),
            Mockito.any(),
            Mockito.eq("IMPORT"),
            Mockito.any(),
            Mockito.any(),
            Mockito.any(),
            Mockito.any(),
            Mockito.any(),
            Mockito.any(),
            Mockito.any(),
            Mockito.any());

    runImport("notify-auditfail-job", datasetId, csv.toString(), "APPEND");

    assertFailedOnceWithNotification("notify-auditfail-job", "", datasetId, name);
    Mockito.verify(asyncJobService, Mockito.never())
        .failJob(Mockito.eq("notify-auditfail-job"), Mockito.startsWith(VALIDATION_FAIL_PREFIX));
  }

  @Test
  void parseException_missingFile_sendsFailureNotificationOnce() throws Exception {
    // given: 존재하지 않는 파일 — 파싱이 예외로 끝나는 catch 경로. 기존부터 알림을 보내던 경로가 그대로 1번인지 확인한다.
    String name = "알림 파싱 예외";
    Long datasetId = createDataset("notify_parse_dataset", name);
    Path missing =
        Path.of(System.getProperty("java.io.tmpdir"), "firehub-test", "no-such-file-wd102.csv");
    Files.deleteIfExists(missing);

    runImport("notify-parse-job", datasetId, missing.toString(), "APPEND");

    assertFailedOnceWithNotification("notify-parse-job", "", datasetId, name);
  }

  @Test
  void success_sendsSuccessNotificationOnce() throws Exception {
    // given: 전량 유효 — 성공 알림 1번, 실패 알림 0번(성공 경로 동작은 바뀌지 않아야 한다)
    String name = "알림 성공";
    Long datasetId = createDataset("notify_success_dataset", name);
    Path csv = writeCsv("code,name\nA001,ok\nA002,ok2\n");

    runImport("notify-success-job", datasetId, csv.toString(), "APPEND");

    Mockito.verify(asyncJobService, Mockito.never())
        .failJob(Mockito.anyString(), Mockito.anyString());
    List<NotificationEvent> events = broadcastImportEvents();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).eventType()).isEqualTo("IMPORT_COMPLETED");
    assertThat(events.get(0).title()).isEqualTo("임포트 완료");
  }
}
