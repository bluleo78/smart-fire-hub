package com.smartfirehub.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PipelineExecutionRepository#hasRunningExecution} 통합 테스트(최종 리뷰 I1 — 고아 staging 스윕의 파이프라인
 * 게이트).
 *
 * <p>{@code pipeline_execution} 은 RLS 대상이라, 이 조회가 스스로 트랜잭션을 열어 GUC 를 세우지 못하면 0행(=거짓 "실행 없음")이 되어
 * 스윕이 살아있는 스테이징을 지운다. 모의 객체로는 이 함정이 보이지 않으므로 실제 DB 로 고정한다. 그래서 테스트 트랜잭션은 끄고(검증 대상 호출이 테스트 트랜잭션의 GUC
 * 를 빌려 쓰지 않게) 픽스처만 {@code inTenantFixture} 안에서 만든다. 다른 테스트가 남긴 기본 테넌트의 실행에 흔들리지 않도록 스크래치 테넌트 둘을 쓴다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineExecutionRunningGateTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PipelineExecutionRepository repository;

  private Long userId;
  private Long runningTenant;
  private Long idleTenant;

  @BeforeEach
  void setUp() {
    userId = TenantRlsTestSupport.insertUser(dsl, "i1_gate_user_");
    runningTenant = TenantRlsTestSupport.createActiveTenant(dsl, "i1-running");
    idleTenant = TenantRlsTestSupport.createActiveTenant(dsl, "i1-idle");
    insertExecution(runningTenant, "RUNNING");
    // 끝난 실행·대기 중 실행은 스테이징을 쥐고 있지 않다 — 게이트가 막지 않아야 한다.
    insertExecution(idleTenant, "COMPLETED");
    insertExecution(idleTenant, "FAILED");
  }

  @AfterEach
  void tearDown() {
    TenantRlsTestSupport.cleanupAll(
        () -> deletePipelines(runningTenant),
        () -> deletePipelines(idleTenant),
        () -> TenantRlsTestSupport.deleteTenants(dsl, runningTenant, idleTenant),
        () -> TenantRlsTestSupport.deleteUser(dsl, userId));
  }

  /** 테넌트에 파이프라인 하나와 주어진 상태의 실행 하나를 만든다(RLS 대상이라 그 테넌트 픽스처 트랜잭션 안에서). */
  private void insertExecution(long tenantId, String status) {
    inTenantFixture(
        tenantId,
        () -> {
          Long pipelineId =
              dsl.fetchOne(
                      "INSERT INTO pipeline (name, created_by, tenant_id) VALUES (?, ?, ?) RETURNING id",
                      "i1-gate-" + status + "-" + TenantRlsTestSupport.nextTenantId(),
                      userId,
                      tenantId)
                  .get(0, Long.class);
          dsl.execute(
              "INSERT INTO pipeline_execution (pipeline_id, status, executed_by, tenant_id)"
                  + " VALUES (?, ?, ?, ?)",
              pipelineId,
              status,
              userId,
              tenantId);
        });
  }

  /** 실행은 파이프라인 ON DELETE CASCADE 로 함께 지워진다. */
  private void deletePipelines(Long tenantId) {
    if (tenantId != null) {
      inTenantFixture(
          tenantId, () -> dsl.execute("DELETE FROM pipeline WHERE tenant_id = ?", tenantId));
    }
  }

  @Test
  void runningExecution_inOwnTenantContext_isSeen() {
    // 스윕처럼 테넌트 컨텍스트만 세우고(트랜잭션 없이) 부른다 — 저장소가 스스로 트랜잭션을 열어 GUC 를 넣어야 true 가 나온다.
    boolean running =
        TenantContext.runScopedGet(
            runningTenant, () -> repository.hasRunningExecution(runningTenant));
    assertThat(running).isTrue();
  }

  @Test
  void onlyFinishedExecutions_areNotRunning() {
    boolean running =
        TenantContext.runScopedGet(idleTenant, () -> repository.hasRunningExecution(idleTenant));
    assertThat(running).isFalse();
  }

  @Test
  void runningExecution_isScopedByRls_toTheContextTenant() {
    // 컨텍스트가 다른 테넌트면 RLS 가 그 행을 가린다 — 조회가 정말 테넌트 GUC 아래에서 돈다는 증거다(스윕은 늘 같은 테넌트로 부른다).
    boolean running =
        TenantContext.runScopedGet(idleTenant, () -> repository.hasRunningExecution(runningTenant));
    assertThat(running).isFalse();
  }
}
