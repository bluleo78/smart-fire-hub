package com.smartfirehub.proactive.repository;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

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
        ownerId, name, "prompt", null, "0 0 9 * * *", "Asia/Seoul", true, Map.of());
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
}
