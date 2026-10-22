package com.smartfirehub.ontology.binding;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.mapping.repository.MappingRepository;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
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

// 바인딩 엔드포인트 — OntologyControllerTest와 동일하게 @WebMvcTest + 실제 SecurityConfig/서비스 Import,
// 리포지토리는 @MockitoBean. 인증은 Bearer valid-token + jwt/permission 목.
@WebMvcTest(DatasetOntologyController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, DatasetOntologyService.class})
class DatasetOntologyControllerTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private DatasetOntologyRepository bindingRepository;
  @MockitoBean private OntologyRepository ontologyRepository;
  // WD-184: bind 가 재연결 시 옛 모델 기준 매핑을 지우고 데이터셋 행을 잠근다 — 이 슬라이스 전용 컨텍스트의 목.
  @MockitoBean private MappingRepository mappingRepository;
  @MockitoBean private DatasetRepository datasetRepository;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
  }

  @Test
  void 미바인딩이면_ontologyId가_null() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
    when(bindingRepository.findOntologyIdByDataset(999123L)).thenReturn(Optional.empty());

    mockMvc
        .perform(
            get("/api/v1/datasets/999123/ontology").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ontologyId").value(nullValue()));
  }

  // WD-184: 204 → 200 + 본문. 첫 연결이면 지운 매핑도 이전 모델도 없다.
  @Test
  void PUT_바인딩은_dataset_write로_200과_결과_본문() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:write"));
    when(ontologyRepository.existsById(1L)).thenReturn(true);

    mockMvc
        .perform(
            put("/api/v1/datasets/999124/ontology")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ontologyId\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.datasetId").value(999124))
        .andExpect(jsonPath("$.ontologyId").value(1))
        .andExpect(jsonPath("$.mappingRemoved").value(false))
        .andExpect(jsonPath("$.previousOntologyId").value(nullValue()))
        .andExpect(jsonPath("$.previousOntologyName").value(nullValue()));
  }

  // WD-184: 다른 모델로 재연결해 옛 모델 기준 매핑을 지웠으면 본문이 그 사실과 이전 모델 이름(도메인)을 싣는다 — 웹·AI 도구가 이것으로 안내한다.
  @Test
  void PUT_다른_모델로_재연결하면_본문에_매핑_삭제와_이전_모델을_싣는다() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:write"));
    when(ontologyRepository.existsById(2L)).thenReturn(true);
    when(bindingRepository.findOntologyIdByDataset(999125L)).thenReturn(Optional.of(1L));
    when(mappingRepository.deleteByDatasetExceptOntology(999125L, 2L)).thenReturn(true);
    when(ontologyRepository.findById(1L))
        .thenReturn(new OntologyResponse("건물모델", 1, List.of(), List.of()));

    mockMvc
        .perform(
            put("/api/v1/datasets/999125/ontology")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ontologyId\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ontologyId").value(2))
        .andExpect(jsonPath("$.mappingRemoved").value(true))
        .andExpect(jsonPath("$.previousOntologyId").value(1))
        .andExpect(jsonPath("$.previousOntologyName").value("건물모델"));
  }

  @Test
  void PUT_바인딩은_존재하지않는_온톨로지면_400() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:write"));
    when(ontologyRepository.existsById(999L)).thenReturn(false);

    mockMvc
        .perform(
            put("/api/v1/datasets/999124/ontology")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ontologyId\":999}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void PUT_바인딩은_dataset_write_없으면_403() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));

    mockMvc
        .perform(
            put("/api/v1/datasets/999124/ontology")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ontologyId\":1}"))
        .andExpect(status().isForbidden());
  }
}
