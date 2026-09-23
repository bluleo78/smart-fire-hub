package com.smartfirehub.settings.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.settings.dto.AiCredentialUpsertRequest;
import com.smartfirehub.settings.dto.OpencodeProbeRequest;
import com.smartfirehub.settings.model.AiCredentialSlot;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialUpsert;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialView;
import com.smartfirehub.settings.service.OpencodeProbeService;
import com.smartfirehub.settings.service.OpencodeProbeService.ProbeResult;
import com.smartfirehub.settings.service.SettingsService;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 테넌트 화면의 AI 자격증명 전용 엔드포인트.
 *
 * <p>{@code ai.credential} 은 하위 필드(secret)에 비밀이 있는 JSON 문서라 범용
 * {@code /api/v1/settings}(문자열 키·값 전제) 경로에 얹을 수 없다 — {@link AiCredentialService}
 * 클래스 javadoc "왜 SettingsService 가 아닌가" 절 참고. 그래서 이 키 하나만을 위한 별도
 * 컨트롤러가 존재한다.
 *
 * <p><b>권한은 세 라우트(GET/PUT/POST probe) 전부 {@code ai:settings} 다.</b> {@code SettingsController} 가 이미 세운
 * 규칙(조회·저장·연결 테스트가 같은 권한)을 그대로 잇는다 — 프로브도 예외가 아니다. 프로브가
 * 인증된 외부 호출(임의 baseURL 에 Bearer 전송)을 만드는 것은 맞지만, 이미 그 호출을 일으킬 수
 * 있는 사람(=자격증명을 저장할 수 있는 사람)에게 프로브가 새로운 능력을 주는 게 아니다. 다만
 * "쓰기 권한이 필요하다"(설계서 「권한」 절, Ruling #9)는 요구는 이 권한 하나로 이미 충족된다.
 * ({@code ai:read} 라는 권한 코드 자체는 존재한다 — AI 세션 조회용(V12)이고, 이 컨트롤러가 다루는
 * {@code ai.credential} 과는 무관한 리소스다. 근거는 {@code ai:read} 부재가 아니라 "이
 * 라우트들이 원래 {@code ai:settings} 하나로 묶여 있었고 그것을 쪼갤 이유가 없었다"는 쪽에 둔다.)
 *
 * <p>{@code DELETE} 는 없다 — 복귀할 플랫폼 기본값이 없으므로 값은 {@code PUT} 으로 덮어쓴다.
 */
@RestController
@RequestMapping("/api/v1/settings/ai-credential")
@RequiredArgsConstructor
public class AiCredentialController {

  private final AiCredentialService aiCredentialService;
  private final OpencodeProbeService opencodeProbeService;
  private final SettingsService settingsService;
  private final OpencodePutValidator opencodePutValidator;

  /**
   * 화면용 조회. {@link AiCredentialService#read} 를 그대로 노출한다 — 미설정이면
   * {@code configured=false}.
   */
  @GetMapping
  @RequirePermission("ai:settings")
  public ResponseEntity<AiCredentialView> get() {
    return ResponseEntity.ok(aiCredentialService.read());
  }

  /**
   * 저장. opencode 는 서비스에 넘기기 전에 {@link OpencodePutValidator} 로 몇 가지를 더 검증한다(설계서
   * "저장 시 검증" 절 — 유형별 필수 필드 자체는 {@link AiCredentialService#save} 가 여전히 지킨다).
   */
  @PutMapping
  @RequirePermission("ai:settings")
  public ResponseEntity<?> put(
      Authentication authentication, @RequestBody AiCredentialUpsertRequest request) {
    if ("opencode".equals(request.agentType())) {
      // 채팅 경로는 저장된 ai.model 과 대조한다(순환 잠금 이유 — OpencodeCredentialValidation 참고).
      String storedAiModel = settingsService.getValue("ai.model").orElse(null);
      Optional<ResponseEntity<Map<String, Object>>> rejected =
          opencodePutValidator.validate(
              request.payload(), request.secret(), storedAiModel, AiCredentialSlot.CHAT);
      if (rejected.isPresent()) return rejected.get();
    }
    Long userId = (Long) authentication.getPrincipal();
    aiCredentialService.save(toUpsert(request), userId);
    return ResponseEntity.noContent().build();
  }

  /**
   * opencode 모델 목록 조회. 요청 값을 검증 없이 그대로 {@link OpencodeProbeService#probe} 에
   * 전달한다.
   *
   * <p><b>여기서 apiKey 를 직접 해석하지 않는다.</b> {@code apiKey} 생략 시 "저장된 값 재사용"
   * 판정(유형 필터·baseURL 일치 검사 포함)은 이미 {@code OpencodeProbeService}(현재 테넌트 행만
   * 본다, {@code AiCredentialService#tenantOpencodeCredential} 참고)가 구현하고 있다 — 같은
   * 판정을 이 계층에 두 번 두지 않는다.
   */
  @PostMapping("/probe")
  @RequirePermission("ai:settings")
  public ResponseEntity<Map<String, Object>> probe(@RequestBody OpencodeProbeRequest request) {
    ProbeResult result = opencodeProbeService.probe(request.baseURL(), request.apiKey());
    return ResponseEntity.ok(OpencodeCredentialValidation.probeBody(result));
  }

  private static AiCredentialUpsert toUpsert(AiCredentialUpsertRequest request) {
    return new AiCredentialUpsert(request.agentType(), request.payload(), request.secret());
  }
}
