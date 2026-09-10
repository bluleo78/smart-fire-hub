package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.dto.SchemaInfoResponse;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * #596 회귀 — {@link AnalyticsQueryExecutionService#getSchemaInfo(List)} 가 <b>스스로 트랜잭션을 열지
 * 못하면</b> {@code TenantAwareTransactionManager.doBegin()} 이 실행되지 않아 {@code app.tenant_id}
 * GUC 가 주입되지 않고, RLS 가 걸린 {@code dataset}/{@code dataset_column} 조회가 fail-closed 로 0행이
 * 되어 {@code datasetId}/{@code datasetName} 이 항상 {@code null} 인 채 반환된다(형제 메서드 {@link
 * AnalyticsQueryExecutionService#execute} 는 자가 {@code @Transactional} 이 있어 이 결함이 없었다).
 *
 * <p><b>클래스 레벨 {@code @Transactional} 을 쓰지 않는 이유(중요).</b> 기존 {@code
 * AnalyticsQueryExecutionServiceTest} 는 클래스 레벨 {@code @Transactional} 로 픽스처 생성과 검증 대상
 * 호출을 <b>같은 테스트 트랜잭션</b> 안에서 실행해 왔다 — 이 경우 {@code
 * TransactionalTestExecutionListener} 가 연 그 테스트 트랜잭션 자체가 {@code doBegin()} 을 한 번 태워
 * GUC 를 주입해 버리므로, 검증 대상 메서드에 {@code @Transactional} 이 있든 없든 결과가 똑같이
 * "정상"으로 보인다 — 이번 결함을 <b>몇 달째 가려 온</b> 공허한 테스트 패턴이다(실측: 이 수정 전
 * 코드에 대해 기존 클래스의 동일 시나리오 테스트를 그대로 돌려도 통과했다). 이 클래스는 그래서
 * {@link IntegrationTestBase#inTenantFixture} 로 픽스처만 커밋하고, 검증 대상 호출은 그 블록
 * <b>밖</b>(운영과 동일한 무-트랜잭션 상태)에서 실행한다.
 */
class AnalyticsQueryExecutionServiceSchemaGucTest extends IntegrationTestBase {

  @Autowired private AnalyticsQueryExecutionService executionService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  @Test
  void getSchemaInfo_datasetIdsFilter_returnsNonNullDatasetId_withoutAmbientTransaction() {
    Long userId = inTenantFixture(this::createTestUser);
    Long datasetId =
        inTenantFixture(
            () ->
                datasetService
                    .createDataset(
                        new CreateDatasetRequest(
                            "Schema GUC Regression",
                            "schema_guc_regr",
                            null,
                            null,
                            "TABLE", "SOURCE",
                            List.of(
                                new DatasetColumnRequest(
                                    "col_x", "X", "TEXT", null, true, false, null)),
                            null),
                        userId)
                    .id());

    try {
      // 검증 대상 호출 — 여기가 핵심이다. 트랜잭션 없이(운영에서 자가 @Transactional 이 없을 때와
      // 동일한 조건) 직접 호출해야 doBegin() 미실행 결함이 재현/검증된다.
      SchemaInfoResponse res = executionService.getSchemaInfo(List.of(datasetId));

      assertThat(res.tables())
          .extracting(SchemaInfoResponse.TableInfo::tableName)
          .containsExactly("schema_guc_regr");
      assertThat(res.tables().get(0).datasetId()).isEqualTo(datasetId);
      assertThat(res.tables().get(0).datasetName()).isEqualTo("Schema GUC Regression");
    } finally {
      // 클래스 레벨 @Transactional 이 없어 자동 롤백되지 않으므로 직접 정리한다(공유 test DB 오염 방지).
      Long id = datasetId;
      Long uid = userId;
      inTenantFixture(
          () -> {
            datasetService.deleteDataset(id);
            // audit_log 가 user 를 FK 참조하므로 user 삭제 전에 먼저 지운다.
            dsl.deleteFrom(DSL.table(DSL.name("audit_log")))
                .where(DSL.field(DSL.name("audit_log", "user_id"), Long.class).eq(uid))
                .execute();
            dsl.deleteFrom(DSL.table(DSL.name("user")))
                .where(DSL.field(DSL.name("user", "id"), Long.class).eq(uid))
                .execute();
            return null;
          });
    }
  }

  /** 회귀 검증 전용 최소 테스트 유저를 만든다. */
  private Long createTestUser() {
    return dsl.insertInto(DSL.table(DSL.name("user")))
        .set(DSL.field(DSL.name("user", "username"), String.class), "schemaguc596")
        .set(DSL.field(DSL.name("user", "password"), String.class), "password")
        .set(DSL.field(DSL.name("user", "name"), String.class), "Schema Guc Test User")
        .set(DSL.field(DSL.name("user", "email"), String.class), "schemaguc596@example.com")
        .returning(DSL.field(DSL.name("user", "id"), Long.class))
        .fetchOne()
        .get(DSL.field(DSL.name("user", "id"), Long.class));
  }
}
