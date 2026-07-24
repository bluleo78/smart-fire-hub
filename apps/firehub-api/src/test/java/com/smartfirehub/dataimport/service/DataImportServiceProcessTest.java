package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.job.service.AsyncJobService;
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
 * Task2: 임포트 잡(processImport)이 검증(Pass1, fail-fast)과 삽입(Pass2)을 완전히 분리해, 오류가 하나라도 있으면
 * 부분 적재 없이 0행으로 실패해야 함을 검증한다. 기존(옛) 계약은 "일부 유효 행은 보존"(#7/#168/#169, APPEND 부분 성공)이었으나,
 * 이 잡의 fail-fast 재설계로 뒤집힌다 — 이 계약 변경 자체가 이 테스트 클래스의 핵심 목적이다.
 */
@Transactional
class DataImportServiceProcessTest extends IntegrationTestBase {

  @Autowired private DataImportService dataImportService;

  @Autowired private DatasetService datasetService;

  @Autowired private DSLContext dsl;

  @MockitoSpyBean private AsyncJobService asyncJobService;

  private Long testUserId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(com.smartfirehub.jooq.Tables.USER)
            .set(com.smartfirehub.jooq.Tables.USER.USERNAME, "process_test_user")
            .set(com.smartfirehub.jooq.Tables.USER.PASSWORD, "password")
            .set(com.smartfirehub.jooq.Tables.USER.NAME, "Process Test User")
            .set(com.smartfirehub.jooq.Tables.USER.EMAIL, "process_test@example.com")
            .returning(com.smartfirehub.jooq.Tables.USER.ID)
            .fetchOne()
            .getId();
  }

  private Long createDatasetWithBigintColumn(String tableName) {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, false, false, null),
            new DatasetColumnRequest("amount", "Amount", "INTEGER", null, false, false, null));

    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Process Fail-Fast Dataset " + tableName,
                tableName,
                "fail-fast 회귀 테스트용",
                null,
                "TABLE",
                "SOURCE",
                columns,
                null),
            testUserId);
    return dataset.id();
  }

  /**
   * badRowIndex(1-based, 헤더 제외 데이터 행 기준)가 -1이면 전량 유효, 그 외에는 해당 행의 amount를 비숫자 값으로 채운다.
   */
  private Path writeCsv(int rows, int badRowIndex) throws Exception {
    StringBuilder csv = new StringBuilder("name,amount\n");
    for (int i = 1; i <= rows; i++) {
      String amount = (i == badRowIndex) ? "notanumber" : String.valueOf(1000 + i);
      csv.append("User").append(i).append(",").append(amount).append("\n");
    }
    Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "firehub-test");
    Files.createDirectories(tempDir);
    Path tempFile = Files.createTempFile(tempDir, "process-test-", ".csv");
    Files.writeString(tempFile, csv.toString(), StandardCharsets.UTF_8);
    return tempFile;
  }

  private long countRows(String tableName) {
    return dsl.fetchCount(
        dsl.select()
            .from(org.jooq.impl.DSL.table(org.jooq.impl.DSL.name("data", tableName))));
  }

  @Test
  void append_failsFast_noPartialInsert_whenErrorInLaterBatch() throws Exception {
    // given: BIGINT(INTEGER) 컬럼 'amount' 데이터셋, 5000행 중 4001번째 행만 비숫자(불량) — 3번째 배치(4000~)에서 오류
    String tableName = "process_failfast_dataset";
    Long datasetId = createDatasetWithBigintColumn(tableName);
    Path csv = writeCsv(5000, 4001);

    // when: APPEND 모드로 processImport 직접 호출 (Jobrunr 잡 메서드의 실제 테스트 진입점)
    dataImportService.processImport(
        "process-failfast-job-id",
        datasetId,
        csv.toString(),
        "",
        "",
        "process_failfast.csv",
        Files.size(csv),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND");

    // then: 첫 오류에서 중단 → target 테이블에 0행(부분 적재 없음), 잡은 실패 처리
    assertThat(countRows(tableName)).isZero();

    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    Mockito.verify(asyncJobService).failJob(Mockito.eq("process-failfast-job-id"), msg.capture());
    // 매핑 없는(no-mapping) 경로이므로 validate()의 영문 오류 메시지("Row 4001: ...")를 통해
    // rowIndexBase가 파일 전역 기준으로 정확히 threading됐음을 확인한다.
    assertThat(msg.getValue()).contains("4001");
  }

  @Test
  void append_insertsAll_whenAllValid() throws Exception {
    String tableName = "process_allvalid_dataset";
    Long datasetId = createDatasetWithBigintColumn(tableName);
    Path csv = writeCsv(5000, -1);

    dataImportService.processImport(
        "process-allvalid-job-id",
        datasetId,
        csv.toString(),
        "",
        "",
        "process_allvalid.csv",
        Files.size(csv),
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND");

    assertThat(countRows(tableName)).isEqualTo(5000);
    Mockito.verify(asyncJobService, Mockito.never()).failJob(Mockito.anyString(), Mockito.anyString());
  }
}
