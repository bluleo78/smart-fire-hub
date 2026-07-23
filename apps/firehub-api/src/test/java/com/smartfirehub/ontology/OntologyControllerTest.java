package com.smartfirehub.ontology;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.ontology.controller.OntologyController;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.UpdateOntologyRequest;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.ontology.service.OntologyService;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// OntologyController PUT 엔드포인트 테스트 — GraphIngestControllerTest 패턴을 따라
// @WebMvcTest + 인증/권한 mock으로 @RequirePermission("ontology:write") 게이팅과
// 버전 충돌(409)/검증 실패(400) 매핑을 검증한다.
@WebMvcTest(OntologyController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, OntologyService.class})
class OntologyControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private OntologyRepository ontologyRepository;
  @MockitoBean private AuditLogService auditLogService;
  @MockitoBean private UserRepository userRepository;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  private static final UpdateOntologyRequest VALID_REQUEST =
      new UpdateOntologyRequest(
          "화재조사 보고서",
          1,
          List.of(new OntologyResponse.EntityType("Incident", "설명", "명명", "exact", List.of())),
          List.of());

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
  }

  // (c) ontology:write 권한이 없으면(예: 일반 USER의 dataset:read만 보유) 403.
  @Test
  void updateOntology_는_ontology_write_권한이_없으면_403을_반환한다() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));

    mockMvc
        .perform(
            put("/api/v1/ontology")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(VALID_REQUEST)))
        .andExpect(status().isForbidden());
  }

  // (b) 리포지토리가 버전 충돌을 IllegalStateException으로 던지면 전역 핸들러가 409로 매핑한다.
  @Test
  void updateOntology_는_버전_충돌_시_409를_반환한다() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("ontology:write"));
    when(ontologyRepository.updateOntology(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("버전 충돌"));

    mockMvc
        .perform(
            put("/api/v1/ontology")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(VALID_REQUEST)))
        .andExpect(status().isConflict());
  }

  // (e) resolution이 embedding|exact가 아니면 서비스 검증에서 IllegalArgumentException → 400.
  @Test
  void updateOntology_는_잘못된_resolution이면_400을_반환한다() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("ontology:write"));
    UpdateOntologyRequest invalid =
        new UpdateOntologyRequest(
            "d",
            1,
            List.of(new OntologyResponse.EntityType("Incident", "d", "n", "invalid", List.of())),
            List.of());

    mockMvc
        .perform(
            put("/api/v1/ontology")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalid)))
        .andExpect(status().isBadRequest());
  }

  // (a) 유효 요청 + 권한 보유 시 200과 갱신된 스키마 버전을 반환한다.
  @Test
  void updateOntology_는_유효하면_갱신본을_200으로_반환한다() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("ontology:write"));
    when(ontologyRepository.updateOntology(org.mockito.ArgumentMatchers.any())).thenReturn(2);
    when(ontologyRepository.findOntology())
        .thenReturn(
            new OntologyResponse(
                "화재조사 보고서",
                2,
                List.of(new OntologyResponse.EntityType("Incident", "설명", "명명", "exact", List.of())),
                List.of()));

    mockMvc
        .perform(
            put("/api/v1/ontology")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(VALID_REQUEST)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schemaVersion").value(2));
  }
}
