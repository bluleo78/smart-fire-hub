package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.support.ConcurrentCalls;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PausedTransactionRace;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * WD-616 — 같은 파이프라인을 동시에 여러 번 지울 때 실제로 지운 1건만 성공하고 나머지는 순차 두 번째 삭제와 같은 404, 「삭제」 감사는 1건인지 본다(WD-584
 * 후속).
 *
 * <p>파이프라인 삭제는 행 잠금(PipelineRepository#lockById — SELECT … FOR UPDATE)이 존재 확인이라, 진 쪽은 먼저 지운 쪽 커밋을
 * 기다린 뒤 행을 찾지 못해 404 로 끝난다(삭제 행 수를 따로 볼 필요가 없다). 이 계약이 깨지지 않도록 고정한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 각 요청이 실제처럼 자기 트랜잭션으로 커밋해야 서로를 기다린다.
 */
class PipelineConcurrentDeleteTest extends IntegrationTestBase {

  @Autowired private PipelineService pipelineService;
  @Autowired private DSLContext dsl;

  private Long userId;
  private String suffix;

  /** 정리 대상 — 테스트가 실패해 남은 파이프라인. */
  private final List<Long> pipelines = new ArrayList<>();

  @BeforeEach
  void setUp() {
    suffix = Long.toString(System.nanoTime());
    userId = TenantRlsTestSupport.insertUser(dsl, "wd616_pl_");
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID);
    inTenantFixture(() -> TenantRlsTestSupport.grantAdminRole(dsl, userId));
  }

  @AfterEach
  void tearDown() {
    inTenantFixture(
        () -> {
          for (Long p : pipelines) {
            dsl.execute("DELETE FROM pipeline WHERE id = ?", p);
          }
          dsl.execute("DELETE FROM audit_log WHERE user_id = ?", userId);
        });
    TenantRlsTestSupport.deleteMembership(dsl, userId);
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }

  /** 스텝 없는 파이프라인 — 삭제 경합만 본다. */
  private Long createPipeline(String label) {
    Long id =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest("wd616-" + suffix + "-" + label, null, List.of()), userId)
            .id();
    pipelines.add(id);
    return id;
  }

  private int deleteAudits(Long pipelineId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE action_type = 'DELETE' AND resource ="
                        + " 'pipeline' AND resource_id = ?",
                    String.valueOf(pipelineId))
                .get(0, Integer.class));
  }

  @Test
  void 같은_파이프라인을_6건_동시에_지우면_1건만_성공하고_나머지는_404다() throws Exception {
    Long id = createPipeline("six");

    List<Throwable> results =
        ConcurrentCalls.run(
            6, DEFAULT_TEST_TENANT_ID, () -> pipelineService.deletePipeline(id, userId));

    assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
    assertThat(results.stream().filter(Objects::nonNull).toList())
        .hasSize(5)
        .allMatch(PipelineNotFoundException.class::isInstance);
    assertThat(deleteAudits(id)).isEqualTo(1);
  }

  @Test
  void 지운_트랜잭션이_커밋되기_전_같은_삭제는_기다렸다가_404이고_감사는_1건이다() throws Exception {
    Long id = createPipeline("paused");

    PausedTransactionRace.Outcome<Void> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> pipelineService.deletePipeline(id, userId),
            () -> {
              pipelineService.deletePipeline(id, userId);
              return null;
            });

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError()).isInstanceOf(PipelineNotFoundException.class);
    assertThat(deleteAudits(id)).isEqualTo(1);
  }

  @Test
  void 순차로_두_번_지우면_두_번째는_404다() {
    Long id = createPipeline("seq");

    pipelineService.deletePipeline(id, userId);

    assertThatThrownBy(() -> pipelineService.deletePipeline(id, userId))
        .isInstanceOf(PipelineNotFoundException.class);
    assertThat(deleteAudits(id)).isEqualTo(1);
  }
}
