package com.smartfirehub.audit.service;

import static com.smartfirehub.jooq.Tables.AUDIT_LOG;
import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Transactional
class AuditLogServiceTest extends IntegrationTestBase {

  /** 필터·검색 동작만 보는 테스트라 모든 행을 보는 ADMIN 자격으로 조회한다(데이터셋 가시성은 AuditLogDatasetVisibilityTest). */
  private static final Clearance ADMIN =
      new Clearance(0L, 0L, Integer.MAX_VALUE, Set.of(), true, Set.of());

  @Autowired private AuditLogService auditLogService;

  @Autowired private DSLContext dsl;

  @Autowired private PasswordEncoder passwordEncoder;

  private Long adminId;
  private Long userId;

  @BeforeEach
  void setUp() {
    // 다른 테스트 클래스(비트랜잭션)가 커밋한 audit_log 잔류 데이터 제거
    dsl.deleteFrom(AUDIT_LOG).execute();

    adminId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "admin@example.com")
            .set(USER.PASSWORD, passwordEncoder.encode("password"))
            .set(USER.NAME, "Admin")
            .set(USER.EMAIL, "admin@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "user1@example.com")
            .set(USER.PASSWORD, passwordEncoder.encode("password"))
            .set(USER.NAME, "User1")
            .set(USER.EMAIL, "user1@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    auditLogService.log(
        adminId,
        "admin",
        "CREATE",
        "dataset",
        "1",
        "데이터셋 생성",
        "127.0.0.1",
        "Mozilla/5.0",
        "SUCCESS",
        null,
        null);
    auditLogService.log(
        adminId,
        "admin",
        "UPDATE",
        "dataset",
        "1",
        "데이터셋 수정",
        "127.0.0.1",
        "Mozilla/5.0",
        "SUCCESS",
        null,
        null);
    auditLogService.log(
        userId,
        "user1",
        "LOGIN",
        "user",
        null,
        "로그인 성공",
        "192.168.1.1",
        "Chrome",
        "SUCCESS",
        null,
        null);
    auditLogService.log(
        userId,
        "user1",
        "IMPORT",
        "dataset",
        "2",
        "CSV 임포트 실패",
        "192.168.1.1",
        "Chrome",
        "FAILURE",
        "파일 형식 오류",
        Map.of("filename", "data.csv"));
  }

  @Test
  void getAuditLogs_noFilter_returnsAll() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(ADMIN, null, null, null, null, null, null, null, 0, 20);

    assertThat(result.content()).hasSize(4);
    assertThat(result.totalElements()).isEqualTo(4);
    assertThat(result.page()).isEqualTo(0);
  }

  @Test
  void getAuditLogs_searchByUsername_filtersResults() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(ADMIN, "user1", null, null, null, null, null, null, 0, 20);

    assertThat(result.content()).hasSize(2);
    assertThat(result.content()).allMatch(log -> log.username().equals("user1"));
  }

  @Test
  void getAuditLogs_searchByDescription_filtersResults() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(ADMIN, "임포트", null, null, null, null, null, null, 0, 20);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).description()).contains("임포트");
  }

  @Test
  void getAuditLogs_filterByActionType_filtersResults() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(ADMIN, null, null, "CREATE", null, null, null, null, 0, 20);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).actionType()).isEqualTo("CREATE");
  }

  @Test
  void getAuditLogs_filterByResource_filtersResults() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(ADMIN, null, null, null, "user", null, null, null, 0, 20);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).resource()).isEqualTo("user");
  }

  @Test
  void getAuditLogs_filterByResult_filtersResults() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(ADMIN, null, null, null, null, "FAILURE", null, null, 0, 20);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).result()).isEqualTo("FAILURE");
    assertThat(result.content().get(0).errorMessage()).isEqualTo("파일 형식 오류");
  }

  @Test
  void getAuditLogs_combinedFilters_filtersResults() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(
            ADMIN, null, null, "CREATE", "dataset", "SUCCESS", null, null, 0, 20);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).username()).isEqualTo("admin");
    assertThat(result.content().get(0).actionType()).isEqualTo("CREATE");
  }

  @Test
  void getAuditLogs_pagination_works() {
    PageResponse<AuditLogResponse> page0 =
        auditLogService.getAuditLogs(ADMIN, null, null, null, null, null, null, null, 0, 2);

    assertThat(page0.content()).hasSize(2);
    assertThat(page0.totalElements()).isEqualTo(4);
    assertThat(page0.totalPages()).isEqualTo(2);
    assertThat(page0.page()).isEqualTo(0);

    PageResponse<AuditLogResponse> page1 =
        auditLogService.getAuditLogs(ADMIN, null, null, null, null, null, null, null, 1, 2);

    assertThat(page1.content()).hasSize(2);
    assertThat(page1.page()).isEqualTo(1);
  }

  @Test
  void getAuditLogs_orderedByActionTimeDesc() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(ADMIN, null, null, null, null, null, null, null, 0, 20);

    for (int i = 0; i < result.content().size() - 1; i++) {
      assertThat(result.content().get(i).actionTime())
          .isAfterOrEqualTo(result.content().get(i + 1).actionTime());
    }
  }

  @Test
  void getAuditLogs_withMetadata_returnsJsonString() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(ADMIN, null, null, "IMPORT", null, null, null, null, 0, 20);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).metadata()).contains("data.csv");
  }

  @Test
  void getAuditLogs_noMatch_returnsEmpty() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(
            ADMIN, "nonexistent", null, null, null, null, null, null, 0, 20);

    assertThat(result.content()).isEmpty();
    assertThat(result.totalElements()).isEqualTo(0);
  }

  /**
   * 날짜 범위 필터 — 미래 startDate 지정 시 결과 없음 (setUp에서 삽입된 모든 로그의 actionTime은 현재 시점이므로 미래 날짜로 필터하면 빈 결과)
   */
  @Test
  void getAuditLogs_startDateInFuture_returnsEmpty() {
    LocalDateTime futureDate = LocalDateTime.now().plusDays(1);
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(ADMIN, null, null, null, null, null, futureDate, null, 0, 20);

    assertThat(result.content()).isEmpty();
    assertThat(result.totalElements()).isEqualTo(0);
  }

  /** 날짜 범위 필터 — 과거 endDate 지정 시 결과 없음 (setUp에서 삽입된 모든 로그의 actionTime은 현재 시점이므로 과거 날짜로 필터하면 빈 결과) */
  @Test
  void getAuditLogs_endDateInPast_returnsEmpty() {
    LocalDateTime pastDate = LocalDateTime.now().minusDays(1);
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(ADMIN, null, null, null, null, null, null, pastDate, 0, 20);

    assertThat(result.content()).isEmpty();
    assertThat(result.totalElements()).isEqualTo(0);
  }

  /** 날짜 범위 필터 — 충분히 넓은 범위 지정 시 전체 결과 반환 */
  @Test
  void getAuditLogs_dateRangeCoveringAll_returnsAll() {
    LocalDateTime startDate = LocalDateTime.now().minusDays(1);
    LocalDateTime endDate = LocalDateTime.now().plusDays(1);
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(
            ADMIN, null, null, null, null, null, startDate, endDate, 0, 20);

    assertThat(result.content()).hasSize(4);
    assertThat(result.totalElements()).isEqualTo(4);
  }

  /**
   * userId 필터 (#89) — 특정 사용자 ID 정확 일치 필터링. username free-text 검색은 부분 일치이므로 동명이인/오타 가능, userId는 정확
   * 일치로 노이즈 제거.
   */
  @Test
  void getAuditLogs_filterByUserId_returnsOnlyThatUsersLogs() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(ADMIN, null, userId, null, null, null, null, null, 0, 20);

    // user1 로그 2개(LOGIN, IMPORT)만 반환되어야 함
    assertThat(result.content()).hasSize(2);
    assertThat(result.totalElements()).isEqualTo(2);
    assertThat(result.content()).allMatch(log -> log.userId().equals(userId));
  }

  @Test
  void getAuditLogs_filterByUserId_admin_returnsOnlyAdminLogs() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(ADMIN, null, adminId, null, null, null, null, null, 0, 20);

    // admin 로그 2개(CREATE, UPDATE)만 반환되어야 함
    assertThat(result.content()).hasSize(2);
    assertThat(result.content()).allMatch(log -> log.userId().equals(adminId));
  }

  /**
   * 회귀 테스트(WD-394): 호출처가 IP·User-Agent 를 넘기지 않아도 처리 중인 요청의 접속 주소·User-Agent 로 채운다 — 데이터셋·역할·멤버 등 감사
   * 도우미가 null 을 넘겨 화면 IP 칸이 「-」였다.
   */
  @Test
  void log_withoutIp_fillsFromCurrentRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.7");
    request.addHeader("User-Agent", "Firefox/1.0");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      Long id =
          auditLogService.log(
              adminId, "admin", "DELETE", "dataset", "9", "데이터셋 삭제", null, null, "SUCCESS", null,
              null);

      AuditLogResponse saved = auditLogService.findById(id).orElseThrow();
      assertThat(saved.ipAddress()).isEqualTo("198.51.100.7");
      assertThat(saved.userAgent()).isEqualTo("Firefox/1.0");
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  /** 호출처가 넘긴 IP(임포트·로그인처럼 직접 고른 값)는 요청 값으로 덮지 않고, 요청 밖(스케줄·비동기 잡)은 비운 채 남긴다(WD-394). */
  @Test
  void log_explicitIpKept_andNoRequestLeavesNull() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.7");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    Long explicit;
    try {
      explicit =
          auditLogService.log(
              adminId,
              "admin",
              "IMPORT",
              "dataset",
              "9",
              "파일 임포트: a.csv",
              "203.0.113.5",
              "Chrome",
              "SUCCESS",
              null,
              null);
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
    Long noRequest =
        auditLogService.log(
            null,
            "system",
            "EXECUTE",
            "pipeline",
            "3",
            "파이프라인 실행",
            null,
            null,
            "SUCCESS",
            null,
            null);

    assertThat(auditLogService.findById(explicit).orElseThrow().ipAddress())
        .isEqualTo("203.0.113.5");
    AuditLogResponse scheduled = auditLogService.findById(noRequest).orElseThrow();
    assertThat(scheduled.ipAddress()).isNull();
    assertThat(scheduled.userAgent()).isNull();
  }
}
