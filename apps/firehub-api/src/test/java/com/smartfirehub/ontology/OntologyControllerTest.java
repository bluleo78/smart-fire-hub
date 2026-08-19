package com.smartfirehub.ontology;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.smartfirehub.ontology.dto.OntologySummary;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.ontology.service.OntologyService;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.user.repository.UserRepository;
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

// OntologyController 엔드포인트 테스트 — GraphIngestControllerTest 패턴을 따라
// @WebMvcTest + 인증/권한 mock으로 @RequirePermission 게이팅을 검증한다.
// (Task 7) 전체 스키마 교체 PUT(/ontology, /ontology/{id})은 요소 단위 편집 API로 대체되어 삭제됐다 —
// 그 경로가 검증하던 검증 실패(400) 매핑은 element 패키지의 요소 단위 테스트(EntityTypeElementTest 등)가
// 이어받는다. (Task 7 리뷰 I-4 정정) 버전 충돌(409)은 이어받은 것이 아니라 개념 자체가 사라졌다 — 요소
// 단위 편집은 요소 하나만 바꾸므로 낙관적 잠금이 필요 없고(OntologyElementService 클래스 주석 참조),
// 어떤 요소 요청 DTO에도 baseVersion 필드가 없다.
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

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
  }

  // 목록 라우트 — dataset:read 권한으로 200, 리포지토리 findAllSummaries("active") 스텁.
  // (status 쿼리 파라미터 도입으로 무필터 기본값이 active-only가 됨 — 무인자 findAllSummaries()가 아니다.)
  @Test
  void 온톨로지_목록_라우트가_200() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
    when(ontologyRepository.findAllSummaries("active"))
        .thenReturn(
            List.of(
                new OntologySummary(
                    1L, "화재조사 보고서", 1, "active", 0, 0, java.time.OffsetDateTime.now(), false)));

    mockMvc
        .perform(get("/api/v1/ontologies").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1))
        // 리포지토리는 isDefault=false로 채워 넣지만(mock 위 코드) 서비스가 id=1을 보고 true로 덮어써야
        // 한다 — OntologyService.withDefaultFlag의 계산과, boolean isDefault의 Jackson 직렬화 이름이
        // "isDefault"로 유지되는지(isXxx 스트리핑 없이)를 함께 고정한다.
        .andExpect(jsonPath("$[0].isDefault").value(true));
  }

  // 무필터 기본값은 active만 — 바인딩 후보 목록이 draft·archived를 집어가지 않게 한다.
  @Test
  void listOntologies_는_기본적으로_active만_조회한다() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
    when(ontologyRepository.findAllSummaries("active")).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/ontologies").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk());

    verify(ontologyRepository).findAllSummaries("active");
  }

  // 관리 다이얼로그는 status=all로 전체를 본다.
  @Test
  void listOntologies_는_status_all이면_필터_없이_조회한다() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
    when(ontologyRepository.findAllSummaries((String) null)).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/ontologies")
                .param("status", "all")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk());

    verify(ontologyRepository).findAllSummaries((String) null);
  }

  // 알 수 없는 상태 문자열은 400 — 오타가 조용히 빈 목록으로 새어나가지 않게 한다.
  @Test
  void listOntologies_는_알_수_없는_status를_거부한다() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));

    mockMvc
        .perform(
            get("/api/v1/ontologies")
                .param("status", "retired")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isBadRequest());
  }

  // id 스코프 단건 조회 — findById(2) 스텁.
  @Test
  void id_스코프_단건조회_라우트가_200() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
    when(ontologyRepository.findById(2L))
        .thenReturn(new OntologyResponse("판매", 1, List.of(), List.of()));

    mockMvc
        .perform(get("/api/v1/ontology/2").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.domain").value("판매"));
  }

  // 생성 라우트 — ontology:write 권한 없으면 403(게이팅 검증).
  @Test
  void 생성_라우트는_ontology_write_없으면_403() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));

    mockMvc
        .perform(
            post("/api/v1/ontologies")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new com.smartfirehub.ontology.dto.CreateOntologyRequest(
                            "판매",
                            List.of(
                                new OntologyResponse.EntityType(
                                    "Customer", "고객", "표기 그대로", "exact", List.of())),
                            List.of()))))
        .andExpect(status().isForbidden());
  }

  // 생성 라우트 — 권한 보유 시 201과 발급 id를 반환한다.
  @Test
  void 생성_라우트는_유효하면_201과_id를_반환한다() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("ontology:write"));
    when(ontologyRepository.createOntology(org.mockito.ArgumentMatchers.any())).thenReturn(7L);

    mockMvc
        .perform(
            post("/api/v1/ontologies")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new com.smartfirehub.ontology.dto.CreateOntologyRequest(
                            "판매",
                            List.of(
                                new OntologyResponse.EntityType(
                                    "Customer", "고객", "표기 그대로", "exact", List.of())),
                            List.of()))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$").value(7));
  }
}
