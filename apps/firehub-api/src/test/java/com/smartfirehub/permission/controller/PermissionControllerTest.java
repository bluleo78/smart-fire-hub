package com.smartfirehub.permission.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.dto.PermissionResponse;
import com.smartfirehub.permission.service.PermissionService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PermissionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class PermissionControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PermissionService permissionService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  private void mockAuthentication(String... permissions) {
    when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
  }

  @Test
  void getPermissions_withPermission_returnsList() throws Exception {
    mockAuthentication("permission:read");
    List<PermissionResponse> permissions =
        List.of(
            new PermissionResponse(1L, "user:read", "Read users", "user"),
            new PermissionResponse(2L, "user:write", "Write users", "user"));
    when(permissionService.getAllPermissions()).thenReturn(permissions);

    mockMvc
        .perform(get("/api/v1/permissions").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("user:read"))
        .andExpect(jsonPath("$[1].code").value("user:write"));
  }

  @Test
  void getPermissions_withCategoryFilter_returnsList() throws Exception {
    mockAuthentication("permission:read");
    List<PermissionResponse> permissions =
        List.of(new PermissionResponse(1L, "user:read", "Read users", "user"));
    when(permissionService.getPermissionsByCategory("user")).thenReturn(permissions);

    mockMvc
        .perform(
            get("/api/v1/permissions")
                .param("category", "user")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].category").value("user"));
  }

  @Test
  void getPermissions_unauthenticated_returnsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/permissions")).andExpect(status().isForbidden());
  }
}
