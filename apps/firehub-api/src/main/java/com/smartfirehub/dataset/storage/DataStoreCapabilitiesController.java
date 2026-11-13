package com.smartfirehub.dataset.storage;

import com.smartfirehub.vectorstore.VectorStoreFeatures;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 테넌트 저장소의 기능 지원 여부를 알려 준다(WD-80).
 *
 * <p>테넌트 구성원이면 누구나 볼 수 있어야 하므로 {@code @RequirePermission} 없이 인증만 요구한다(/api/v1/** 는 SecurityConfig
 * 에서 인증 필수).
 */
@RestController
@RequestMapping("/api/v1/data-store")
@RequiredArgsConstructor
public class DataStoreCapabilitiesController {

  private final DataStoreResolver dataStoreResolver;
  private final VectorStoreFeatures vectorStoreFeatures;

  /**
   * 백엔드별 기능 지원 여부 — 판정은 {@link DataStoreBackend} 에 둔다(지금은 PostgreSQL 만 GEOMETRY·행 검색을 지원). WD-636:
   * VARCHAR 최대 길이도 함께 내려 웹 폼이 저장소 상한으로 미리 막게 한다. WD-659: 길이 미지정 VARCHAR 의 실제 길이(저장소 기본 길이)도 내려 웹 행
   * 폼이 서버 행 API 와 같은 한도로 판정하게 한다.
   */
  @GetMapping("/capabilities")
  public DataStoreCapabilitiesResponse capabilities() {
    DataStoreBackend backend = dataStoreResolver.currentBackend();
    return new DataStoreCapabilitiesResponse(
        backend.name(),
        backend.supportsGeometry(),
        vectorStoreFeatures.supportsRowSearch(backend), // 행 검색 판정은 벡터 저장소 조합(ADR 0002 결정 3)
        backend.maxVarcharLength(),
        backend.defaultVarcharLength());
  }
}
