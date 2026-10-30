package com.smartfirehub.settings.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.settings.model.AiCredentialSlot;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 워크스페이스 AI·메일 설정 변경 감사 기록(WD-465). AiCredentialService(자격증명 등록·수정·삭제)와 SettingsService(AI 동작
 * 설정·SMTP 설정 변경·삭제)가 성공 경로 끝에서 부른다. 자격증명·SMTP 는 데이터가 외부로 나가는 경로(어느 공급자·어느 메일 서버로 보내는가)를 바꾸는 행위라 누가
 * 언제 바꿨는지 남겨야 한다.
 *
 * <p><b>resource</b>: {@code ai_credential}(resourceId 는 슬롯 이름 CHAT/CLASSIFY — {@code
 * ai_provider_hosting} 과 같은 관례), {@code ai_settings}(resourceId {@code ai}), {@code
 * smtp_settings}(resourceId {@code smtp}). 설정은 워크스페이스에 하나뿐이라 id 대신 묶음 이름을 쓴다.
 *
 * <p><b>action</b>: 기존 값만 쓴다 — 처음 등록은 CREATE, 바뀐 항목이 있는 저장은 UPDATE, 해제는 DELETE.
 *
 * <p><b>남기지 않는 것</b>: 감사 화면은 audit:read 만 있으면 볼 수 있다. API 키·OAuth 토큰·SMTP 비밀번호·시스템 프롬프트 본문 같은 값은
 * 설명·metadata 어디에도 싣지 않고, 바뀐 항목의 <b>이름</b>({@code changedFields})만 싣는다. 바뀐 것이 없으면 호출부가 부르지 않거나 여기서
 * 생략한다.
 *
 * <p>호출 트랜잭션 안에서 쓰므로 이후 같은 트랜잭션에서 예외가 나면 감사 행도 함께 롤백된다 — 거부된 요청(400/403)은 기록이 남지 않는다. 행위자 username
 * 은 감사 테이블 NOT NULL 이라 조회해서 넣는다(없으면 "unknown", 행위자 없는 내부 경로는 "system"). IP·User-Agent 는 다른 도메인 변경
 * 감사처럼 남기지 않는다(null).
 */
@Component
@RequiredArgsConstructor
public class SettingsAuditRecorder {

  /** AI 자격증명 감사 resource. */
  public static final String RESOURCE_AI_CREDENTIAL = "ai_credential";

  /** AI 동작 설정(모델·시스템 프롬프트·턴 등) 감사 resource. */
  public static final String RESOURCE_AI_SETTINGS = "ai_settings";

  /** 워크스페이스 SMTP 설정 감사 resource. */
  public static final String RESOURCE_SMTP_SETTINGS = "smtp_settings";

  /** 자격증명 항목 이름 → 설명용 라벨. 라벨이 없는 이름(장래 payload 필드)은 이름 그대로 쓴다. */
  private static final Map<String, String> CREDENTIAL_LABELS =
      Map.of(
          "agentType", "유형",
          "apiKey", "API 키",
          "oauthToken", "OAuth 토큰",
          "providerId", "공급자",
          "baseURL", "기본 URL",
          "reasoningEffort", "추론 강도",
          "hosting", "호스팅 위치",
          "model", "모델");

  /** 설정 키 → 설명용 라벨(AI 동작 6키 + SMTP 6키). */
  private static final Map<String, String> SETTING_LABELS =
      Map.ofEntries(
          Map.entry("ai.model", "모델"),
          Map.entry("ai.max_turns", "최대 턴 수"),
          Map.entry("ai.system_prompt", "시스템 프롬프트"),
          Map.entry("ai.temperature", "온도"),
          Map.entry("ai.max_tokens", "최대 토큰"),
          Map.entry("ai.session_max_tokens", "세션 최대 토큰"),
          Map.entry("smtp.host", "호스트"),
          Map.entry("smtp.port", "포트"),
          Map.entry("smtp.username", "사용자 이름"),
          Map.entry("smtp.password", "비밀번호"),
          Map.entry("smtp.starttls", "STARTTLS"),
          Map.entry("smtp.from_address", "발신자 주소"));

  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  /**
   * 자격증명 등록 감사 — 「AI 자격증명 등록: 채팅」. 행이 없던(또는 손상돼 읽을 수 없던) 슬롯에 처음 저장한 경우다.
   *
   * @param agentType 저장된 유형(sdk/cli/cli-api/opencode — 비밀 아님)
   */
  public void credentialCreated(Long userId, AiCredentialSlot slot, String agentType) {
    Map<String, Object> metadata = credentialMetadata(slot, agentType, null);
    record(
        userId,
        "CREATE",
        RESOURCE_AI_CREDENTIAL,
        slot.name(),
        "AI 자격증명 등록: " + slotLabel(slot),
        metadata);
  }

  /**
   * 자격증명 수정 감사 — 「AI 자격증명 수정: 채팅 (바뀐 항목: 유형·API 키)」. 바뀐 항목이 없으면 남기지 않는다.
   *
   * @param changedFields 저장 전후 문서를 비교해 모은 항목 이름(agentType·payload 키·비밀 이름·분류 model) — 값은 담지 않는다
   */
  public void credentialUpdated(
      Long userId, AiCredentialSlot slot, String agentType, List<String> changedFields) {
    if (changedFields.isEmpty()) {
      return; // 실제 변경 없음 — 감사 생략
    }
    record(
        userId,
        "UPDATE",
        RESOURCE_AI_CREDENTIAL,
        slot.name(),
        "AI 자격증명 수정: "
            + slotLabel(slot)
            + " (바뀐 항목: "
            + labels(changedFields, CREDENTIAL_LABELS)
            + ")",
        credentialMetadata(slot, agentType, changedFields));
  }

  /** 자격증명 해제 감사 — 「AI 자격증명 삭제: 분류」. 유형은 지우기 전에 읽은 값을 받는다(손상돼 읽을 수 없으면 빈 문자열). */
  public void credentialDeleted(Long userId, AiCredentialSlot slot, String agentType) {
    record(
        userId,
        "DELETE",
        RESOURCE_AI_CREDENTIAL,
        slot.name(),
        "AI 자격증명 삭제: " + slotLabel(slot),
        credentialMetadata(slot, agentType, null));
  }

  /** AI 동작 설정 수정 감사 — 「AI 설정 수정 (바뀐 항목: 모델·시스템 프롬프트)」. 바뀐 키가 없으면 남기지 않는다. */
  public void aiSettingsUpdated(Long userId, List<String> changedKeys) {
    if (changedKeys.isEmpty()) {
      return; // 실제 변경 없음 — 감사 생략
    }
    record(
        userId,
        "UPDATE",
        RESOURCE_AI_SETTINGS,
        "ai",
        "AI 설정 수정 (바뀐 항목: " + labels(changedKeys, SETTING_LABELS) + ")",
        settingsMetadata("AI 설정", changedKeys));
  }

  /**
   * SMTP 설정 저장 감사 — 처음 등록이면 「SMTP 설정 등록」(CREATE), 아니면 「SMTP 설정 수정 (바뀐 항목: 호스트·비밀번호)」(UPDATE). 바뀐 키가
   * 없으면 남기지 않는다.
   *
   * @param created 저장 전에 SMTP 키가 하나도 없었는가
   */
  public void smtpSaved(Long userId, boolean created, List<String> changedKeys) {
    if (changedKeys.isEmpty()) {
      return; // 실제 변경 없음 — 감사 생략
    }
    String description =
        created
            ? "SMTP 설정 등록 (항목: " + labels(changedKeys, SETTING_LABELS) + ")"
            : "SMTP 설정 수정 (바뀐 항목: " + labels(changedKeys, SETTING_LABELS) + ")";
    record(
        userId,
        created ? "CREATE" : "UPDATE",
        RESOURCE_SMTP_SETTINGS,
        "smtp",
        description,
        settingsMetadata("SMTP 설정", changedKeys));
  }

  /**
   * SMTP 설정 해제 감사 — 「SMTP 설정 삭제」. 지워진 키 이름을 {@code changedFields} 로 싣는다.
   *
   * @param clearedKeys 지우기 전에 저장돼 있던 SMTP 키 — 비어 있으면(이미 미설정) 남기지 않는다
   */
  public void smtpDeleted(Long userId, List<String> clearedKeys) {
    if (clearedKeys.isEmpty()) {
      return; // 지울 것이 없었다(멱등 해제) — 감사 생략
    }
    record(
        userId,
        "DELETE",
        RESOURCE_SMTP_SETTINGS,
        "smtp",
        "SMTP 설정 삭제",
        settingsMetadata("SMTP 설정", clearedKeys));
  }

  /** 슬롯 설명용 라벨. */
  private static String slotLabel(AiCredentialSlot slot) {
    return slot == AiCredentialSlot.CLASSIFY ? "분류" : "채팅";
  }

  private static Map<String, Object> credentialMetadata(
      AiCredentialSlot slot, String agentType, List<String> changedFields) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", slotLabel(slot));
    metadata.put("slot", slot.name());
    metadata.put("agentType", agentType);
    if (changedFields != null) {
      metadata.put("changedFields", List.copyOf(changedFields));
    }
    return metadata;
  }

  private static Map<String, Object> settingsMetadata(String name, List<String> changedKeys) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", name);
    metadata.put("changedFields", List.copyOf(changedKeys));
    return metadata;
  }

  /** 항목 이름을 설명용 한국어 라벨로 잇는다(예: "모델·시스템 프롬프트"). 라벨이 없는 이름은 그대로 쓴다. */
  private static String labels(List<String> names, Map<String, String> labelMap) {
    return String.join("·", names.stream().map(n -> labelMap.getOrDefault(n, n)).toList());
  }

  /** 감사 행 한 줄. */
  private void record(
      Long userId,
      String action,
      String resource,
      String resourceId,
      String description,
      Map<String, Object> metadata) {
    String actorName =
        userId == null
            ? "system"
            : userRepository.findById(userId).map(UserResponse::username).orElse("unknown");
    auditLogService.log(
        userId,
        actorName,
        action,
        resource,
        resourceId,
        description,
        null,
        null,
        "SUCCESS",
        null,
        metadata);
  }
}
