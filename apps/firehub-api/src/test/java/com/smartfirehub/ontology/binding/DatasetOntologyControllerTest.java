package com.smartfirehub.ontology.binding;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.permission.service.PermissionService;
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

  @Autowired private MockMvc mockMvc;

  @MockitoBean private DatasetOntologyRepository bindingRepository;
  @MockitoBean private OntologyRepository ontologyRepository;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
  }

  @Test
  void 미바인딩이면_ontologyId가_null() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
    when(bindingRepository.findOntologyIdByDataset(999123L)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/datasets/999123/ontology").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ontologyId").value(nullValue()));
  }

  @Test
  void PUT_바인딩은_dataset_write로_204() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:write"));
    when(ontologyRepository.existsById(1L)).thenReturn(true);

    mockMvc
        .perform(
            put("/api/v1/datasets/999124/ontology")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ontologyId\":1}"))
        .andExpect(status().isNoContent());
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
