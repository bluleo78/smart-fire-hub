package com.smartfirehub.proactive.repository;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.proactive.dto.ProactiveJobExecutionSummaryResponse;
import com.smartfirehub.proactive.dto.ReportListItemResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProactiveJobExecutionRepository.findReportsByUserId 통합 테스트.
 *
 * <p>"리포트"의 정의(COMPLETED + htmlContent 존재)와 사용자 소유권 스코핑이 쿼리 수준에서 지켜지는지 검증한다.
 */
@Transactional
class ProactiveJobExecutionRepositoryTest extends IntegrationTestBase {

  @Autowired private ProactiveJobExecutionRepository repository;
  @Autowired private ProactiveJobRepository jobRepository;
  @Autowired private DSLContext dsl;

  private Long userId;
  private Long otherUserId;

  @BeforeEach
  void setUp() {
    userId = createUser("pjerepo");
    otherUserId = createUser("pjerepo_other");
  }

  private Long createUser(String prefix) {
    String unique = prefix + "_" + System.nanoTime();
    return dsl.insertInto(USER)
        .set(USER.USERNAME, unique)
        .set(USER.PASSWORD, "password")
        .set(USER.NAME, "PJE Repo Tester")
        .set(USER.EMAIL, unique + "@example.com")
        .returning(USER.ID)
        .fetchOne()
        .getId();
  }

  private Long createJob(Long ownerId, String name) {
    return jobRepository.create(
        ownerId, name, "prompt", null, "0 0 9 * * *", "Asia/Seoul", true, null, Map.of());
  }

  /** COMPLETED + htmlContent 있는 실행 = 리포트 */
  private Long createReport(Long jobId, String title, String summary) {
    Long execId = repository.create(jobId);
    repository.updateResult(
        execId,
        "COMPLETED",
        Map.of("title", title, "summary", summary, "htmlContent", "<h1>body</h1>"),
        LocalDateTime.now());
    return execId;
  }

  @Test
  void findReportsByUserId_returnsCompletedExecutionsWithHtmlContent() {
    Long jobId = createJob(userId, "월간 화재 통계");
    createReport(jobId, "8월 리포트", "요약문");

    List<ReportListItemResponse> reports = repository.findReportsByUserId(userId, 20, 0);

    assertThat(reports).hasSize(1);
    assertThat(reports.get(0).title()).isEqualTo("8월 리포트");
    assertThat(reports.get(0).summary()).isEqualTo("요약문");
    assertThat(reports.get(0).jobName()).isEqualTo("월간 화재 통계");
    assertThat(reports.get(0).jobId()).isEqualTo(jobId);
  }

  @Test
  void findReportsByUserId_excludesExecutionsWithoutHtmlContent() {
    Long jobId = createJob(userId, "구버전 잡");
    Long execId = repository.create(jobId);
    // htmlContent 없는 구버전 sections 기반 결과 — 뷰어가 404를 내므로 목록에서 제외되어야 한다
    repository.updateResult(
        execId, "COMPLETED", Map.of("title", "구버전", "sections", List.of()), LocalDateTime.now());

    assertThat(repository.findReportsByUserId(userId, 20, 0)).isEmpty();
  }

  @Test
  void findReportsByUserId_excludesBlankHtmlContent() {
    Long jobId = createJob(userId, "빈 본문 잡");
    Long execId = repository.create(jobId);
    repository.updateResult(
        execId, "COMPLETED", Map.of("title", "빈 본문", "htmlContent", ""), LocalDateTime.now());

    assertThat(repository.findReportsByUserId(userId, 20, 0)).isEmpty();
  }

  @Test
  void findReportsByUserId_excludesFailedExecutions() {
    Long jobId = createJob(userId, "실패 잡");
    Long execId = repository.create(jobId);
    repository.updateResult(
        execId, "FAILED", Map.of("htmlContent", "<h1>x</h1>"), LocalDateTime.now());

    assertThat(repository.findReportsByUserId(userId, 20, 0)).isEmpty();
  }

  @Test
  void findReportsByUserId_excludesOtherUsersReports() {
    Long otherJobId = createJob(otherUserId, "남의 잡");
    createReport(otherJobId, "남의 리포트", "남의 요약");

    assertThat(repository.findReportsByUserId(userId, 20, 0)).isEmpty();
  }

  @Test
  void findReportsByUserId_fallsBackToJobNameWhenTitleBlank() {
    Long jobId = createJob(userId, "제목없는잡");
    Long execId = repository.create(jobId);
    repository.updateResult(
        execId, "COMPLETED", Map.of("title", "  ", "htmlContent", "<p>x</p>"), LocalDateTime.now());

    List<ReportListItemResponse> reports = repository.findReportsByUserId(userId, 20, 0);

    assertThat(reports).hasSize(1);
    assertThat(reports.get(0).title()).isEqualTo("제목없는잡");
  }

  @Test
  void findReportsByUserId_appliesLimitAndOffset() {
    Long jobId = createJob(userId, "다건 잡");
    createReport(jobId, "첫번째", "s1");
    createReport(jobId, "두번째", "s2");
    createReport(jobId, "세번째", "s3");

    assertThat(repository.findReportsByUserId(userId, 2, 0)).hasSize(2);
    assertThat(repository.findReportsByUserId(userId, 2, 2)).hasSize(1);
  }

  /**
   * findSummariesByJobId 통합 테스트 (#604).
   *
   * <p>목록 endpoint 전용 경량 뷰가 리포트 본문(result)을 아예 담지 않으면서도 상태/시간 등 요약 정보와
   * limit/offset 페이징은 findByJobId와 동일하게 동작하는지 검증한다.
   */
  @Test
  void findSummariesByJobId_excludesResultButKeepsSummaryFields() {
    Long jobId = createJob(userId, "요약 뷰 잡");
    Long execId = createReport(jobId, "리포트 제목", "리포트 요약");
    repository.updateDeliveredChannels(execId, List.of("CHAT", "EMAIL"));

    List<ProactiveJobExecutionSummaryResponse> summaries =
        repository.findSummariesByJobId(jobId, 20, 0);

    assertThat(summaries).hasSize(1);
    ProactiveJobExecutionSummaryResponse summary = summaries.get(0);
    assertThat(summary.id()).isEqualTo(execId);
    assertThat(summary.jobId()).isEqualTo(jobId);
    assertThat(summary.status()).isEqualTo("COMPLETED");
    assertThat(summary.deliveredChannels()).containsExactly("CHAT", "EMAIL");
    // ProactiveJobExecutionSummaryResponse에는 result 필드 자체가 없다 — 리포트 본문 미포함을 타입 레벨로 보장
  }

  @Test
  void findSummariesByJobId_appliesLimitAndOffsetOrderedByIdDesc() {
    Long jobId = createJob(userId, "요약 페이징 잡");
    Long first = createReport(jobId, "첫번째", "s1");
    Long second = createReport(jobId, "두번째", "s2");
    Long third = createReport(jobId, "세번째", "s3");

    List<ProactiveJobExecutionSummaryResponse> page1 = repository.findSummariesByJobId(jobId, 2, 0);
    List<ProactiveJobExecutionSummaryResponse> page2 = repository.findSummariesByJobId(jobId, 2, 2);

    assertThat(page1).extracting(ProactiveJobExecutionSummaryResponse::id).containsExactly(third, second);
    assertThat(page2).extracting(ProactiveJobExecutionSummaryResponse::id).containsExactly(first);
  }
}
