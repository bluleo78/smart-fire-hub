package com.smartfirehub.settings.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.settings.dto.ResolvedSettingResponse;
import com.smartfirehub.settings.dto.UpdateSettingsRequest;
import com.smartfirehub.settings.service.SettingsService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsController {

  private final SettingsService settingsService;

  /**
   * 테넌트 화면용 설정 조회. <b>해석된</b> 값(오버라이드가 있으면 그 값)과 함께
   * {@code overridden}/{@code tenantEditable} 플래그를 돌려준다.
   *
   * <p>P7-b 이전에는 {@code getByPrefix} 를 불러 <b>플랫폼 기본값만</b> 돌려줬다. 그대로 두면
   * 테넌트가 오버라이드를 저장한 뒤 화면을 다시 불러도 예전 값이 그대로 보여서 <b>저장이 아무
   * 일도 하지 않은 것처럼</b> 보인다 — 쓰기 경로만 고치고 읽기 경로를 잊으면 생기는 어긋남이다.
   *
   * <p>엔드포인트를 새로 만들지 않고 이 자리를 교체한 이유: 소비자가 web 설정 화면 하나뿐이고,
   * 운영자 평면은 이미 {@code GET /api/platform/settings}({@code getAll}) 를 쓴다. 두 개를 두면
   * "어느 쪽이 진짜 화면용인가"가 계속 갈린다. 대신 응답 형태가 바뀌므로 web 의 타입 정의와
   * Playwright 스펙이 함께 바뀐다.
   */
  @GetMapping
  @RequirePermission("ai:settings")
  public ResponseEntity<List<ResolvedSettingResponse>> getSettings(@RequestParam String prefix) {
    return ResponseEntity.ok(settingsService.getResolvedByPrefix(prefix));
  }

  // GET /ai-api-key 는 P7-b Task 7 에서 삭제했다. ai:settings 를 가진 테넌트 관리자에게
  // ai.api_key 복호화 평문을 그대로 돌려주던 경로인데, P7-b 가 ai.api_key 를 플랫폼 소유로
  // 확정하므로 그대로 두면 "테넌트 관리자가 플랫폼 자격증명을 평문으로 읽는다"가 된다.
  // 다른 모든 읽기 경로는 maskSecret 을 지나 **** 만 내보내는데 여기만 예외였다.
  // 소비자는 없었다(web 의 #ai-api-key 는 입력 필드 HTML id, ai-agent 는 역호출 구조를 이미 버렸다).
  // 키가 설정됐는지 여부가 필요하면 GET /settings?prefix=ai 의 마스킹된 값으로 판별한다.

  @PutMapping
  @RequirePermission("ai:settings")
  public ResponseEntity<Void> updateSettings(
      Authentication authentication, @Valid @RequestBody UpdateSettingsRequest request) {
    Long userId = (Long) authentication.getPrincipal();
    settingsService.updateSettings(request.settings(), userId);
    return ResponseEntity.noContent().build();
  }

  /**
   * 테넌트 오버라이드를 지워 플랫폼 값으로 되돌린다. <b>멱등</b> — 오버라이드가 이미 없어도(=이미
   * 상속 중) 204 다. "상속 중" 은 오류 상태가 아니므로 404 로 만들지 않는다.
   *
   * <p><b>경로가 {@code /overrides/{key}} 인 이유.</b> 처음에는 {@code /{key}} 였는데, 그러면 이
   * 컨트롤러의 <b>미매핑 하위 경로를 전부 삼키는 catch-all</b> 이 된다. 실제로 {@code DELETE
   * /settings/smtp} 가 {@code key="smtp"} 로 흡수됐고, Task 7 에서 삭제한 {@code GET /ai-api-key} 가
   * 404 가 아니라 405 가 된 것도 같은 흡수였다. 무해한 사례만 있었지만 위험은 구조적이다 —
   * 이 컨트롤러에 하위 경로를 추가하는 사람이 <b>매번</b> 그 흡수를 기억해야 하고, 잊으면 새 경로가
   * 조용히 {@code ai:settings} 권한 게이트를 물려받는다(P7-a 의 "인터셉터 경로 등록 누락"과 같은
   * 계열). 세그먼트를 하나 두면 흡수가 <b>불가능</b>해지므로, 사람의 주의력에 맡기던 것을 라우팅으로
   * 옮긴다.
   */
  @DeleteMapping("/overrides/{key}")
  @RequirePermission("ai:settings")
  public ResponseEntity<Void> clearOverride(@PathVariable String key) {
    settingsService.clearOverride(key);
    return ResponseEntity.noContent().build();
  }

  // GET /smtp 는 삭제했다(P7-c1). 이 라우트는 `getByPrefix("smtp")` 를 돌려줬는데 그 경로는
  // <b>테넌트 오버라이드 해석기를 타지 않는다</b> — Task 3 가 발송 경로(getSmtpConfig)를 해석기로
  // 옮긴 뒤로는 "메일은 테넌트 값으로 나가는데 화면은 플랫폼 값을 보여주는" 어긋남을 만드는
  // 라우트가 됐다. 유일한 소비자였던 web SMTP 탭이 GET /settings?prefix=smtp(해석 + 플래그)로
  // 옮겨가 소비자도 0이 됐다. "아무도 안 쓰면서 틀린 값을 주는 경로"를 남기면 다음 호출자가
  // 조용히 그 값을 믿는다 — PUT /smtp 를 지울 때와 같은 이유로 구조에서 없앤다.
  //
  // PUT /smtp 도 삭제돼 있다(P7-b). SMTP 6키가 전부 플랫폼 소유이던 시절 테넌트 평면에서 항상
  // 거부였고, "항상 던지는 서비스 메서드 + 그것을 부르는 라우트"는 거부를 런타임 예외로만 남긴다.
  // 지금 SMTP 쓰기는 다른 키들과 같은 PUT /settings(테넌트) / PUT /api/platform/settings(플랫폼)다.
  //
  // POST /smtp/test 는 남는다 — 값을 노출하지 않는 진단 액션이고 저장된 설정으로 접속한다.

  @PostMapping("/smtp/test")
  @RequirePermission("settings:write")
  public ResponseEntity<Map<String, Object>> testSmtpSettings(Authentication authentication) {
    // SMTP 연결 테스트 — 현재 설정으로 실제 연결 확인
    try {
      Map<String, String> config = settingsService.getSmtpConfig();
      String host = config.getOrDefault("smtp.host", "");
      if (host.isBlank()) {
        return ResponseEntity.ok(Map.of("success", false, "message", "SMTP 호스트가 설정되지 않았습니다"));
      }

      JavaMailSenderImpl sender = new JavaMailSenderImpl();
      sender.setHost(host);
      String portStr = config.getOrDefault("smtp.port", "587");
      sender.setPort(portStr.isBlank() ? 587 : Integer.parseInt(portStr));
      String username = config.getOrDefault("smtp.username", "");
      if (!username.isBlank()) sender.setUsername(username);
      String password = config.getOrDefault("smtp.password", "");
      if (!password.isBlank()) sender.setPassword(password);

      Properties props = sender.getJavaMailProperties();
      props.put("mail.transport.protocol", "smtp");
      props.put("mail.smtp.auth", !username.isBlank() ? "true" : "false");
      props.put("mail.smtp.connectiontimeout", "10000");
      props.put("mail.smtp.timeout", "10000");
      String starttls = config.getOrDefault("smtp.starttls", "true");
      if ("true".equalsIgnoreCase(starttls)) {
        props.put("mail.smtp.starttls.enable", "true");
      }

      sender.testConnection();
      return ResponseEntity.ok(Map.of("success", true, "message", "SMTP 연결 성공"));
    } catch (Exception e) {
      return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
    }
  }
}
