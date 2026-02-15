package com.smartfirehub.audit.controller;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SuppressWarnings("null")
@WebMvcTest(AuditLogController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private JwtProperties jwtProperties;

    @MockitoBean
    private PermissionService permissionService;

    private void mockAuthentication(String... permissions) {
        when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
        when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
    }

    @Test
    void getAuditLogs_withPermission_returnsList() throws Exception {
        mockAuthentication("audit:read");
        AuditLogResponse log = new AuditLogResponse(
                1L, 1L, "admin", "CREATE", "dataset", "5",
                "데이터셋 생성", LocalDateTime.of(2026, 2, 15, 10, 0, 0),
                "127.0.0.1", "Mozilla/5.0", "SUCCESS", null, null
        );
        PageResponse<AuditLogResponse> page = new PageResponse<>(List.of(log), 0, 20, 1, 1);
        when(auditLogService.getAuditLogs(null, null, null, null, 0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value("admin"))
                .andExpect(jsonPath("$.content[0].actionType").value("CREATE"))
                .andExpect(jsonPath("$.content[0].resource").value("dataset"))
                .andExpect(jsonPath("$.content[0].result").value("SUCCESS"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getAuditLogs_withSearchFilter_passesParams() throws Exception {
        mockAuthentication("audit:read");
        PageResponse<AuditLogResponse> page = new PageResponse<>(List.of(), 0, 20, 0, 0);
        when(auditLogService.getAuditLogs("admin", "CREATE", "dataset", "SUCCESS", 0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", "Bearer valid-token")
                        .param("search", "admin")
                        .param("actionType", "CREATE")
                        .param("resource", "dataset")
                        .param("result", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getAuditLogs_withPagination_passesPageParams() throws Exception {
        mockAuthentication("audit:read");
        PageResponse<AuditLogResponse> page = new PageResponse<>(List.of(), 2, 10, 50, 5);
        when(auditLogService.getAuditLogs(null, null, null, null, 2, 10)).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", "Bearer valid-token")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(50))
                .andExpect(jsonPath("$.totalPages").value(5));
    }

    @Test
    void getAuditLogs_withMetadata_returnsRawJson() throws Exception {
        mockAuthentication("audit:read");
        AuditLogResponse log = new AuditLogResponse(
                1L, 1L, "admin", "IMPORT", "dataset", "3",
                "CSV 임포트", LocalDateTime.of(2026, 2, 15, 10, 0, 0),
                "127.0.0.1", null, "SUCCESS", null, "{\"rows\":100}"
        );
        PageResponse<AuditLogResponse> page = new PageResponse<>(List.of(log), 0, 20, 1, 1);
        when(auditLogService.getAuditLogs(null, null, null, null, 0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].metadata.rows").value(100));
    }

    @Test
    void getAuditLogs_unauthenticated_returnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAuditLogs_withoutPermission_returnsForbidden() throws Exception {
        mockAuthentication("user:read");

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }
}
