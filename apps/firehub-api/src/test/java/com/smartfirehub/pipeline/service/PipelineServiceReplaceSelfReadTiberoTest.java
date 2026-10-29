package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.PipelineDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tibero 테넌트의 REPLACE 자기 출력 읽기 저장 거부(WD-426).
 *
 * <p>PG 는 자기 출력을 읽는 SELECT + REPLACE 를 비우기 전 데이터를 읽는 한 문장으로 바꿔 실행하지만, Tibero 는 그 형태가 없고 db-executor
 * 선행문 규칙도 잠금 전용 형태를 받지 않는다 — 그래서 저장에서 막는다. 저장 검증은 이 판정에 {@link PipelineDataSchema#backend()} 만 쓰므로
 * 그 한 메서드만 Tibero 로 바꾼다. 검증기·보안 가드는 PG 그대로라 PG 픽스처(데이터셋 테이블)로 참조 판정이 나온다 — 실제 Tibero 검증기·가드 경로는
 * SqlValidatorTiberoTest·DatasetAccessGuard 테스트가, 실행 시점 거부는 PipelineAsyncRunnerTiberoTest 가 따로
 * 확인한다.
 */
@Transactional
class PipelineServiceReplaceSelfReadTiberoTest extends IntegrationTestBase {

  @Autowired private PipelineService pipelineService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  /** 저장 검증이 보는 테넌트 백엔드 — 각 테스트가 backend() 만 TIBERO 로 바꾼다(나머지 메서드는 실제 동작). */
  @MockitoSpyBean private PipelineDataSchema pipelineDataSchema;

  private Long userId;
  private Long outputDatasetId;

  @BeforeEach
  void setUp() {
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "wd426_tibero_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "WD-426 Tibero")
            .set(USER.EMAIL, "wd426_tibero@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    // 보안 등급(S2): 저장 시 SQL 판정은 편집자 자격으로 한다 — ACTIVE 멤버십 + USER 역할이 있어야 기본 등급 데이터셋을 참조할 수 있다.
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID);
    dsl.execute(
        "insert into user_role (user_id, role_id) select ?, id from role where name = 'USER'",
        userId);
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    outputDatasetId =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "WD426 Tibero Out",
                    "wd426_tibero_out",
                    null,
                    null,
                    "TABLE",
                    "DERIVED",
                    columns,
                    null),
                userId)
            .id();
    datasetService.createDataset(
        new CreateDatasetRequest(
            "WD426 Tibero In", "wd426_tibero_in", null, null, "TABLE", "SOURCE", columns, null),
        userId);
    doReturn(DataStoreBackend.TIBERO).when(pipelineDataSchema).backend();
  }

  private PipelineDetailResponse saveReplaceStep(String sql) {
    return pipelineService.createPipeline(
        new CreatePipelineRequest(
            "WD426 tibero " + sql.hashCode(),
            "WD-426",
            List.of(
                new PipelineStepRequest(
                    "self", null, "SQL", sql, outputDatasetId, null, null, "REPLACE"))),
        userId);
  }

  /** Tibero SELECT + REPLACE 가 출력 자신을 읽으면 저장에서 막고, 대안(MERGE·APPEND·다른 출력)을 안내한다. */
  @Test
  void replaceSelectReadingItsOwnOutput_isRejectedOnTibero() {
    assertThatThrownBy(
            () ->
                saveReplaceStep(
                    "SELECT col1 || 'x' AS col1 FROM " + DataSchema.qualify("wd426_tibero_out")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Tibero 저장소에서는 교체(REPLACE) 스텝의 SQL 이 출력 데이터셋 자신을 읽거나 고칠 수 없습니다")
        .hasMessageContaining("MERGE")
        .hasMessageEndingWith(": self");
  }

  /** Tibero 사용자 DML 이 출력 자신을 고쳐도 같은 Tibero 문구로 막는다(PG 의 「SELECT 로 바꾸라」 안내는 Tibero 에 맞지 않는다). */
  @Test
  void replaceUpdatingItsOwnOutput_isRejectedOnTibero() {
    assertThatThrownBy(
            () ->
                saveReplaceStep(
                    "UPDATE " + DataSchema.qualify("wd426_tibero_out") + " SET col1 = 'U'"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Tibero 저장소에서는");
  }

  /** 막지 않는 것 — 출력을 읽지 않는 Tibero REPLACE SELECT 는 그대로 저장된다. */
  @Test
  void replaceSelectFromAnotherTable_isSavedOnTibero() {
    assertThat(saveReplaceStep("SELECT col1 FROM " + DataSchema.qualify("wd426_tibero_in")).steps())
        .hasSize(1);
  }
}
