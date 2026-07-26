package com.smartfirehub.mapping.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.mapping.dto.MappingResponse;
import com.smartfirehub.mapping.dto.MappingSpec;
import com.smartfirehub.mapping.service.MappingService;
import com.smartfirehub.permission.service.PermissionService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 매핑 엔드포인트 — 서비스는 @MockitoBean, 인증은 Bearer valid-token + jwt/permission 목.
@WebMvcTest(MappingController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class MappingControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private MappingService mappingService;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  private static final String BODY =
      "{\"entities\":[{\"entityType\":\"Incident\",\"nameColumn\":\"id\",\"properties\":[]}],\"relations\":[]}";

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
  }

  private static MappingResponse resp(String status) {
    return new MappingResponse(700L, 1L,
        new MappingSpec(List.of(new MappingSpec.EntityMapping("Incident", "id", List.of())), List.of()), status);
  }

  @Test
  void GET_매핑_있으면_200() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
    when(mappingService.get(700L)).thenReturn(Optional.of(resp("draft")));
    mockMvc.perform(get("/api/v1/datasets/700/mapping").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("draft"))
        .andExpect(jsonPath("$.spec.entities[0].entityType").value("Incident"));
  }

  @Test
  void GET_매핑_없으면_404() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
    when(mappingService.get(700L)).thenReturn(Optional.empty());
    mockMvc.perform(get("/api/v1/datasets/700/mapping").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNotFound());
  }

  @Test
  void PUT_저장은_dataset_write로_200() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:write"));
    when(mappingService.save(eq(700L), any(), eq(1L))).thenReturn(resp("draft"));
    mockMvc.perform(put("/api/v1/datasets/700/mapping")
            .header("Authorization", "Bearer valid-token")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("draft"));
  }

  @Test
  void PUT_저장은_dataset_write_없으면_403() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
    mockMvc.perform(put("/api/v1/datasets/700/mapping")
            .header("Authorization", "Bearer valid-token")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  void POST_activate는_dataset_write로_200() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:write"));
    when(mappingService.activate(700L, 1L)).thenReturn(resp("active"));
    mockMvc.perform(post("/api/v1/datasets/700/mapping/activate")
            .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("active"));
  }

  @Test
  void PUT_잘못된_매핑은_400() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:write"));
    when(mappingService.save(anyLong(), any(), anyLong()))
        .thenThrow(new IllegalArgumentException("온톨로지에 없는 엔티티 타입: NoSuch"));
    mockMvc.perform(put("/api/v1/datasets/700/mapping")
            .header("Authorization", "Bearer valid-token")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isBadRequest());
  }
}
