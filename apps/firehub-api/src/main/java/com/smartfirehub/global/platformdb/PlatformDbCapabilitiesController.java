package com.smartfirehub.global.platformdb;

import com.smartfirehub.vectorstore.VectorStoreFeatures;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 플랫폼 DB 의 선택 기능(벡터·트라이그램 검색) 지원 여부를 알려 준다(WD-495).
 *
 * <p>Tibero 설치본은 두 기능이 모두 꺼져 있어 검색이 늘 빈 결과를 돌려주는데, 사용자·에이전트가 그 사실을 알 수 있게 한다. 인증된 사용자면 누구나 볼 수 있어야
 * 하므로 {@code @RequirePermission} 없이 인증만 요구한다(/api/v1/** 는 SecurityConfig 에서 인증 필수).
 */
@RestController
@RequestMapping("/api/v1/platform-db")
@RequiredArgsConstructor
public class PlatformDbCapabilitiesController {

  private final VectorStoreFeatures features;

  /**
   * 기능별 지원 여부 — 판정은 {@link VectorStoreFeatures} 구현체(platform+PG 는 모두 true, platform+Tibero 는 모두
   * false)가 한다. 저장소 가용성은 Qdrant 조합만 바뀌고 몇 초 캐시된다(WD-724).
   */
  @GetMapping("/capabilities")
  public PlatformDbCapabilitiesResponse capabilities() {
    return new PlatformDbCapabilitiesResponse(
        features.supports(PlatformDbFeature.VECTOR_SEARCH),
        features.supports(PlatformDbFeature.TRIGRAM_SEARCH),
        features.available());
  }
}
