package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doReturn;

import com.smartfirehub.dataset.dto.AddColumnRequest;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 필드명 사전 검사를 지나 카탈로그 유니크 제약에 걸리는 경우(WD-99) — 동시 추가처럼 사전 검사가 미커밋 행을 못 보는 상황을, 사전 검사가 읽는 목록을 빈 목록으로
 * 바꿔 재현한다. 쓰기는 실제 DB 에 가므로, PostgreSQL 이 보고하는 제약 이름이 서비스 상수와 같아야 사전 검사와 같은 409 로 바뀐다.
 */
@Transactional
class DatasetColumnNameRaceTest extends IntegrationTestBase {

  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @MockitoSpyBean private DatasetColumnRepository columnRepository;

  private Long userId;

  @BeforeEach
  void setUp() {
    userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                encoder,
                "wd99_race_user",
                "wd99_race@example.com",
                "Password123",
                "WD99 Race",
                DEFAULT_TEST_TENANT_ID)
            .id();
  }

  @Test
  void addColumn_사전검사를_지난_같은_이름은_유니크제약에서_필드명_중복_409가_된다() {
    DatasetDetailResponse ds =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "WD99 race",
                "wd99_race",
                null,
                null,
                "TABLE",
                "SOURCE",
                List.of(
                    new DatasetColumnRequest("title", "Title", "TEXT", null, true, false, null)),
                null),
            userId);
    // 사전 검사가 기존 필드를 못 보게 한다(동시에 들어온 미커밋 행과 같은 상황).
    doReturn(List.of()).when(columnRepository).findByDatasetId(ds.id());

    Throwable thrown =
        catchThrowable(
            () ->
                datasetService.addColumn(
                    ds.id(),
                    new AddColumnRequest("title", "Dup", "TEXT", null, true, false, null)));

    assertThat(thrown).isInstanceOf(CodedApiException.class);
    CodedApiException coded = (CodedApiException) thrown;
    assertThat(coded.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(coded.code()).isEqualTo(DatasetService.COLUMN_NAME_DUPLICATE_CODE);
    // 제약 위반 경로는 DB 세부 원문에서 필드명을 꺼내지 않는다.
    assertThat(coded.getMessage()).isEqualTo("이미 있는 필드명입니다");
  }
}
