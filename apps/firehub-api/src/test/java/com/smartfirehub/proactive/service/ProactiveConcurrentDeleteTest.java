package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.smartfirehub.proactive.dto.CreateProactiveJobRequest;
import com.smartfirehub.proactive.dto.CreateReportTemplateRequest;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.exception.ProactiveJobNotFoundException;
import com.smartfirehub.support.ConcurrentCalls;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PausedTransactionRace;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * WD-584 — 같은 스마트 작업·리포트 양식을 동시에 여러 번 지우면 모두 204 가 되고 「삭제」 감사가 요청 수만큼 남던 문제(스마트 작업은 예약 해제도 요청 수만큼
 * 반복됐다).
 *
 * <p>진 쪽 요청은 순차 두 번째 삭제와 같은 404({@link ProactiveJobNotFoundException})여야 하고, 감사·예약 해제는 실제로 지운 1건만
 * 남아야 한다. 클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 각 요청이 자기 트랜잭션으로 커밋해야 서로를 기다린다.
 */
class ProactiveConcurrentDeleteTest extends IntegrationTestBase {

  @Autowired private ProactiveJobService jobService;
  @Autowired private ReportTemplateService templateService;
  @Autowired private ProactiveJobAdminService adminService;
  @Autowired private DSLContext dsl;

  /** 실행 러너는 쓰지 않는다 — ProactiveJobOwnershipTest 와 같은 목 구성으로 컨텍스트를 공유한다. */
  @MockitoBean private ProactiveJobAsyncRunner asyncRunner;

  /** 예약 해제 호출 횟수를 센다 — 진 쪽 요청이 해제를 되풀이하지 않아야 한다. */
  @MockitoBean private ProactiveJobSchedulerService schedulerService;

  private Long owner;
  private String suffix;

  @BeforeEach
  void setUp() {
    suffix = Long.toString(System.nanoTime());
    owner = TenantRlsTestSupport.insertUser(dsl, "wd584_pro_");
  }

  @AfterEach
  void tearDown() {
    inTenantFixture(
        () -> {
          dsl.execute("DELETE FROM audit_log WHERE user_id = ?", owner);
          dsl.execute("DELETE FROM proactive_job WHERE user_id = ?", owner);
          dsl.execute("DELETE FROM report_template WHERE user_id = ?", owner);
        });
    TenantRlsTestSupport.deleteUser(dsl, owner);
  }

  // ── 도우미 ────────────────────────────────────────────────────────────────

  /** cron 없는 작업 — 실제 예약은 만들지 않는다(스케줄러는 목). */
  private ProactiveJobResponse createJob(String label) {
    return jobService.createJob(
        new CreateProactiveJobRequest(
            "WD584 " + suffix + " " + label,
            "프롬프트",
            null,
            null,
            null,
            false,
            null,
            Map.of("channels", List.of("CHAT"))),
        owner);
  }

  private ReportTemplateResponse createTemplate(String label) {
    return templateService.createTemplate(
        new CreateReportTemplateRequest(
            "WD584 " + suffix + " " + label,
            null,
            List.of(Map.of("key", "summary", "label", "요약", "type", "text", "instruction", "지시")),
            null),
        owner);
  }

  private int deleteAudits(String resource, Long id) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE action_type = 'DELETE' AND resource = ?"
                        + " AND resource_id = ?",
                    resource,
                    String.valueOf(id))
                .get(0, Integer.class));
  }

  private static void assertOneSuccessRestNotFound(List<Throwable> results) {
    assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
    assertThat(results.stream().filter(Objects::nonNull).toList())
        .hasSize(results.size() - 1)
        .allMatch(ProactiveJobNotFoundException.class::isInstance);
  }

  // ── 스마트 작업 ───────────────────────────────────────────────────────────

  @Test
  void 같은_작업을_6건_동시에_지우면_1건만_성공하고_감사와_예약_해제도_1번이다() throws Exception {
    ProactiveJobResponse job = createJob("six");

    List<Throwable> results =
        ConcurrentCalls.run(6, DEFAULT_TEST_TENANT_ID, () -> jobService.deleteJob(job.id(), owner));

    assertOneSuccessRestNotFound(results);
    assertThat(deleteAudits("proactive_job", job.id())).isEqualTo(1);
    verify(schedulerService, times(1)).unregisterScheduleAfterCommit(job.id());
  }

  @Test
  void 작업을_지운_트랜잭션이_커밋되기_전_같은_삭제는_기다렸다가_404이고_감사와_예약_해제는_1번이다() throws Exception {
    ProactiveJobResponse job = createJob("paused");

    PausedTransactionRace.Outcome<Void> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> jobService.deleteJob(job.id(), owner),
            () -> {
              jobService.deleteJob(job.id(), owner);
              return null;
            });

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError())
        .isInstanceOf(ProactiveJobNotFoundException.class)
        .hasMessage("Proactive Job을 찾을 수 없습니다: " + job.id());
    assertThat(deleteAudits("proactive_job", job.id())).isEqualTo(1);
    verify(schedulerService, times(1)).unregisterScheduleAfterCommit(job.id());
  }

  @Test
  void 작업을_순차로_두_번_지우면_두_번째는_404다() {
    ProactiveJobResponse job = createJob("seq");

    jobService.deleteJob(job.id(), owner);

    assertThatThrownBy(() -> jobService.deleteJob(job.id(), owner))
        .isInstanceOf(ProactiveJobNotFoundException.class);
    assertThat(deleteAudits("proactive_job", job.id())).isEqualTo(1);
    verify(schedulerService, times(1)).unregisterScheduleAfterCommit(job.id());
  }

  // ── 스마트 작업 관리자 삭제(같은 규칙) ─────────────────────────────────────

  private int adminDeleteAudits(Long id) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE action_type = ? AND resource = ?"
                        + " AND resource_id = ?",
                    ProactiveJobAdminService.ACTION_ADMIN_DELETE,
                    "proactive_job",
                    String.valueOf(id))
                .get(0, Integer.class));
  }

  @Test
  void 관리자가_같은_작업을_6건_동시에_지우면_1건만_성공하고_감사와_예약_해제도_1번이다() throws Exception {
    ProactiveJobResponse job = createJob("admin-six");

    List<Throwable> results =
        ConcurrentCalls.run(
            6, DEFAULT_TEST_TENANT_ID, () -> adminService.deleteJob(job.id(), owner));

    assertOneSuccessRestNotFound(results);
    assertThat(adminDeleteAudits(job.id())).isEqualTo(1);
    verify(schedulerService, times(1)).unregisterScheduleAfterCommit(job.id());
  }

  @Test
  void 관리자가_지운_트랜잭션이_커밋되기_전_같은_삭제는_기다렸다가_404다() throws Exception {
    ProactiveJobResponse job = createJob("admin-paused");

    PausedTransactionRace.Outcome<Void> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> adminService.deleteJob(job.id(), owner),
            () -> {
              adminService.deleteJob(job.id(), owner);
              return null;
            });

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError())
        .isInstanceOf(ProactiveJobNotFoundException.class)
        .hasMessage("Proactive Job을 찾을 수 없습니다: " + job.id());
    assertThat(adminDeleteAudits(job.id())).isEqualTo(1);
    verify(schedulerService, times(1)).unregisterScheduleAfterCommit(job.id());
  }

  // ── 리포트 양식 ───────────────────────────────────────────────────────────

  @Test
  void 같은_양식을_6건_동시에_지우면_1건만_성공하고_감사도_1건이다() throws Exception {
    ReportTemplateResponse template = createTemplate("six");

    List<Throwable> results =
        ConcurrentCalls.run(
            6, DEFAULT_TEST_TENANT_ID, () -> templateService.deleteTemplate(template.id(), owner));

    assertOneSuccessRestNotFound(results);
    assertThat(deleteAudits("report_template", template.id())).isEqualTo(1);
  }

  @Test
  void 양식을_지운_트랜잭션이_커밋되기_전_같은_삭제는_기다렸다가_404이고_감사는_1건이다() throws Exception {
    ReportTemplateResponse template = createTemplate("paused");

    PausedTransactionRace.Outcome<Void> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> templateService.deleteTemplate(template.id(), owner),
            () -> {
              templateService.deleteTemplate(template.id(), owner);
              return null;
            });

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError())
        .isInstanceOf(ProactiveJobNotFoundException.class)
        .hasMessage("템플릿을 찾을 수 없습니다: " + template.id());
    assertThat(deleteAudits("report_template", template.id())).isEqualTo(1);
  }

  @Test
  void 양식을_순차로_두_번_지우면_두_번째는_404다() {
    ReportTemplateResponse template = createTemplate("seq");

    templateService.deleteTemplate(template.id(), owner);

    assertThatThrownBy(() -> templateService.deleteTemplate(template.id(), owner))
        .isInstanceOf(ProactiveJobNotFoundException.class);
    assertThat(deleteAudits("report_template", template.id())).isEqualTo(1);
  }
}
