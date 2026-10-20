package com.smartfirehub.ontology.element;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.mapping.dto.OntologyMappingUsage;
import com.smartfirehub.mapping.service.MappingReferenceService;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WD-129: 매핑 사용처 조회·삭제 차단 409 의 HTTP 계약. 웹은 이 JSON 모양(요소 id 문자열 키, 볼 수 없는 데이터셋의 null 이름, 409 의
 * code·errors)에 기대므로, 서비스 단위 테스트가 못 보는 직렬화·권한 배선을 여기서 고정한다.
 */
@WebMvcTest(OntologyElementController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class OntologyElementControllerMappingUsageTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OntologyElementService elementService;
  @MockitoBean private MappingReferenceService mappingReferences;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
  }

  @Test
  void 사용처는_요소_id를_문자열_키로_싣고_볼_수_없는_데이터셋은_개수로만_싣는다() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
    when(mappingReferences.usage(3L))
        .thenReturn(
            new OntologyMappingUsage(
                Map.of(
                    10L,
                    new OntologyMappingUsage.ElementUsage(
                        List.of(new OntologyMappingUsage.UsageDataset(7L, "화재사건표")), 1)),
                Map.of(55L, new OntologyMappingUsage.ElementUsage(List.of(), 2)),
                Map.of()));

    mockMvc
        .perform(
            get("/api/v1/ontology/3/mapping-usage").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entityTypes['10'].datasets[0].datasetId").value(7))
        .andExpect(jsonPath("$.entityTypes['10'].datasets[0].datasetName").value("화재사건표"))
        .andExpect(jsonPath("$.entityTypes['10'].hiddenCount").value(1))
        .andExpect(jsonPath("$.properties['55'].datasets").isEmpty())
        .andExpect(jsonPath("$.properties['55'].hiddenCount").value(2))
        .andExpect(jsonPath("$.relations").isEmpty());
  }

  @Test
  void 사용처_조회는_dataset_read_권한이_없으면_403() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of());

    mockMvc
        .perform(
            get("/api/v1/ontology/3/mapping-usage").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 매핑이_쓰는_요소_삭제는_409_코드와_문구만_싣는다() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("ontology:write"));
    String message = "「Sensor」 타입은 「화재사건표」 데이터셋 매핑에서 사용 중이라 삭제할 수 없습니다. 매핑에서 먼저 빼 주세요.";
    when(elementService.deleteEntityType(3L, 10L))
        .thenThrow(
            new CodedApiException(
                HttpStatus.CONFLICT, MappingReferenceService.IN_USE_CODE, message));

    mockMvc
        .perform(
            delete("/api/v1/ontology/3/entity-types/10")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(MappingReferenceService.IN_USE_CODE))
        .andExpect(jsonPath("$.message").value(message))
        // 존재 은닉 — 데이터셋 id 를 싣지 않는다.
        .andExpect(jsonPath("$.errors").doesNotExist());
  }
}
