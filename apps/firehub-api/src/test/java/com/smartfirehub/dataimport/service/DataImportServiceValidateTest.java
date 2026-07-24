package com.smartfirehub.dataimport.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataimport.dto.ColumnMappingEntry;
import com.smartfirehub.dataimport.dto.ImportValidateResponse;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사전 검증(validateImport)이 전량이 아니라 파일 앞 200행만 샘플링해 검사하는지 검증한다. 대용량 파일에서도 즉시 응답할 수
 * 있도록 하는 것이 목적이며, 이 테스트는 응답 DTO의 sampleSize/sampled 필드와 실제 검사 대상 행수가 일치하는지 확인한다.
 */
@Transactional
class DataImportServiceValidateTest extends IntegrationTestBase {

  @Autowired private DataImportService dataImportService;

  @Autowired private DatasetService datasetService;

  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long testDatasetId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "validate_sample_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Validate Sample User")
            .set(USER.EMAIL, "validate_sample@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("name", "Name", "TEXT", null, false, false, null));

    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Validate Sample Dataset",
                "validate_sample_dataset",
                "Dataset for validateImport sampling test",
                null,
                "TABLE",
                "SOURCE",
                columns,
                null),
            testUserId);

    testDatasetId = dataset.id();
  }

  @Test
  void validateImport_samplesFirst200Rows_andFlagsSampled() throws Exception {
    // given: 헤더 + 300행(모두 유효) CSV — 샘플 크기(200)보다 큰 파일
    StringBuilder csv = new StringBuilder("name\n");
    for (int i = 0; i < 300; i++) {
      csv.append("row").append(i).append("\n");
    }
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "t.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8));
    List<ColumnMappingEntry> mappings = List.of(new ColumnMappingEntry("name", "name"));

    // when
    ImportValidateResponse res = dataImportService.validateImport(testDatasetId, file, mappings);

    // then: 전체 300이 아니라 샘플 200만 검사, sampled=true
    assertThat(res.sampled()).isTrue();
    assertThat(res.sampleSize()).isEqualTo(200);
    assertThat(res.validRows()).isEqualTo(200);
    assertThat(res.errorRows()).isZero();
  }

  @Test
  void validateImport_allInvalidSample_validRowsZero() throws Exception {
    // given: 헤더만 있고 name(필수) 컬럼이 비어있는 300행 CSV — 전부 검증 실패
    StringBuilder csv = new StringBuilder("name\n");
    for (int i = 0; i < 300; i++) {
      csv.append("\n");
    }
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "invalid.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8));
    List<ColumnMappingEntry> mappings = List.of(new ColumnMappingEntry("name", "name"));

    // when
    ImportValidateResponse res = dataImportService.validateImport(testDatasetId, file, mappings);

    // then: 샘플 200행 모두 검사 대상이나 전부 오류 → validRows=0
    assertThat(res.sampled()).isTrue();
    assertThat(res.sampleSize()).isEqualTo(200);
    assertThat(res.validRows()).isZero();
    assertThat(res.errorRows()).isEqualTo(200);
  }
}
