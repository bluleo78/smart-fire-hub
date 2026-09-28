package com.smartfirehub.embedding.config;

import com.smartfirehub.embedding.config.dto.EmbeddingConfigRequest;
import com.smartfirehub.embedding.config.dto.EmbeddingConfigView;
import com.smartfirehub.embedding.config.dto.EmbeddingImpact;
import com.smartfirehub.embedding.config.dto.EmbeddingProbeResponse;
import com.smartfirehub.global.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 테넌트 임베딩 설정 엔드포인트(#713). 권한은 기존 테넌트 설정 쓰기와 같은 {@code ai:settings} —
 * {@code SettingsController}·{@code AiCredentialController} 가 세운 "조회·저장·연결 테스트가 같은 권한" 규칙을 잇는다.
 * DELETE 는 없다 — 돌아갈 플랫폼 값이 없으므로 PUT 으로 덮어쓴다.
 */
@RestController
@RequestMapping("/api/v1/settings/embedding")
@RequiredArgsConstructor
public class EmbeddingSettingsController {

  private final EmbeddingSettingsService settingsService;

  @GetMapping
  @RequirePermission("ai:settings")
  public EmbeddingConfigView get() {
    return settingsService.view();
  }

  /** 저장 없이 실제 임베딩 1건으로 차원을 잰다. 외부 호출을 일으키므로 GET 이 아니다. */
  @PostMapping("/test")
  @RequirePermission("ai:settings")
  public EmbeddingProbeResponse test(@RequestBody EmbeddingConfigRequest request) {
    return settingsService.test(request);
  }

  /** 그 설정으로 저장했을 때의 재임베딩 대상 수(확인 창). 차원이 미지원이면 400. */
  @GetMapping("/impact")
  @RequirePermission("ai:settings")
  public EmbeddingImpact impact(@RequestParam String model, @RequestParam int dimension) {
    return settingsService.impact(model, dimension);
  }

  @PutMapping
  @RequirePermission("ai:settings")
  public EmbeddingConfigView save(
      Authentication authentication, @RequestBody EmbeddingConfigRequest request) {
    return settingsService.save(request, (Long) authentication.getPrincipal());
  }
}
