package com.smartfirehub.audit.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AuditLogServiceTest extends IntegrationTestBase {

  @Autowired private AuditLogService auditLogService;

  @Autowired private DSLContext dsl;

  @Autowired private PasswordEncoder passwordEncoder;

  private Long adminId;
  private Long userId;

  @BeforeEach
  void setUp() {
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
        auditLogService.getAuditLogs(null, null, null, null, 0, 20);

    assertThat(result.content()).hasSize(4);
    assertThat(result.totalElements()).isEqualTo(4);
    assertThat(result.page()).isEqualTo(0);
  }

  @Test
  void getAuditLogs_searchByUsername_filtersResults() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs("user1", null, null, null, 0, 20);

    assertThat(result.content()).hasSize(2);
    assertThat(result.content()).allMatch(log -> log.username().equals("user1"));
  }

  @Test
  void getAuditLogs_searchByDescription_filtersResults() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs("임포트", null, null, null, 0, 20);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).description()).contains("임포트");
  }

  @Test
  void getAuditLogs_filterByActionType_filtersResults() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(null, "CREATE", null, null, 0, 20);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).actionType()).isEqualTo("CREATE");
  }

  @Test
  void getAuditLogs_filterByResource_filtersResults() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(null, null, "user", null, 0, 20);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).resource()).isEqualTo("user");
  }

  @Test
  void getAuditLogs_filterByResult_filtersResults() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(null, null, null, "FAILURE", 0, 20);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).result()).isEqualTo("FAILURE");
    assertThat(result.content().get(0).errorMessage()).isEqualTo("파일 형식 오류");
  }

  @Test
  void getAuditLogs_combinedFilters_filtersResults() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(null, "CREATE", "dataset", "SUCCESS", 0, 20);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).username()).isEqualTo("admin");
    assertThat(result.content().get(0).actionType()).isEqualTo("CREATE");
  }

  @Test
  void getAuditLogs_pagination_works() {
    PageResponse<AuditLogResponse> page0 =
        auditLogService.getAuditLogs(null, null, null, null, 0, 2);

    assertThat(page0.content()).hasSize(2);
    assertThat(page0.totalElements()).isEqualTo(4);
    assertThat(page0.totalPages()).isEqualTo(2);
    assertThat(page0.page()).isEqualTo(0);

    PageResponse<AuditLogResponse> page1 =
        auditLogService.getAuditLogs(null, null, null, null, 1, 2);

    assertThat(page1.content()).hasSize(2);
    assertThat(page1.page()).isEqualTo(1);
  }

  @Test
  void getAuditLogs_orderedByActionTimeDesc() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(null, null, null, null, 0, 20);

    for (int i = 0; i < result.content().size() - 1; i++) {
      assertThat(result.content().get(i).actionTime())
          .isAfterOrEqualTo(result.content().get(i + 1).actionTime());
    }
  }

  @Test
  void getAuditLogs_withMetadata_returnsJsonString() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs(null, "IMPORT", null, null, 0, 20);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).metadata()).contains("data.csv");
  }

  @Test
  void getAuditLogs_noMatch_returnsEmpty() {
    PageResponse<AuditLogResponse> result =
        auditLogService.getAuditLogs("nonexistent", null, null, null, 0, 20);

    assertThat(result.content()).isEmpty();
    assertThat(result.totalElements()).isEqualTo(0);
  }
}
