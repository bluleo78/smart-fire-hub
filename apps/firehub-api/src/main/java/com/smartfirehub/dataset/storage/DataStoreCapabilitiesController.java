package com.smartfirehub.dataset.storage;

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

  /** 백엔드별 기능 지원 여부 — 판정은 {@link DataStoreBackend} 에 둔다(지금은 PostgreSQL 만 GEOMETRY·행 검색을 지원). */
  @GetMapping("/capabilities")
  public DataStoreCapabilitiesResponse capabilities() {
    DataStoreBackend backend = dataStoreResolver.currentBackend();
    return new DataStoreCapabilitiesResponse(
        backend.name(), backend.supportsGeometry(), backend.supportsRowSearch());
  }
}
