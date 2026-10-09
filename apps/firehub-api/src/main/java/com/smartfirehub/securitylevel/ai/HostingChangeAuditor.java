package com.smartfirehub.securitylevel.ai;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AI 공급자 호스팅 선언 변경 감사(공통 결정 R3). 호스팅 선언은 민감 데이터를 어느 공급자로 보낼지 정하는 보안 결정이라, 채팅·분류·임베딩 세 저장 지점에서 값이
 * <b>실제로 바뀐</b> 경우에만 기존 {@link AuditLogService#log} 로 한 건 남긴다. 보안 등급 감사({@code
 * SecurityAuditRecorder})는 흐름 B 소유라 쓰지 않는다.
 *
 * <p>호출부는 저장이 성공한 <b>뒤</b> 저장 전후의 저장된 값을 넘긴다 — 요청 값이 아니라 결과를 비교해야 유형 변경으로 외부로 돌아가는 경우도 잡히고,
 * 거부(400/403)된 저장은 여기까지 오지 않아 기록되지 않는다.
 */
@Component
@RequiredArgsConstructor
public class HostingChangeAuditor {

  /** 감사 action_type. */
  public static final String ACTION = "AI_PROVIDER_HOSTING_CHANGE";

  /** 감사 resource. resource_id 는 슬롯 이름(CHAT/CLASSIFY/EMBEDDING). */
  public static final String RESOURCE = "ai_provider_hosting";

  /** 감사 대상 슬롯. */
  public enum Slot {
    CHAT,
    CLASSIFY,
    EMBEDDING
  }

  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  /** {@code before != after} 일 때만 감사 1건. userId 가 없으면(시스템 경로) "system" 으로 남긴다 — 행을 떨구지 않는다. */
  public void recordIfChanged(
      Long userId, Slot slot, ProviderHosting before, ProviderHosting after) {
    if (before == after) {
      return;
    }
    String actorName =
        userId == null
            ? "system"
            : userRepository.findById(userId).map(UserResponse::username).orElse("unknown");
    auditLogService.log(
        userId,
        actorName,
        ACTION,
        RESOURCE,
        slot.name(),
        "AI 공급자 호스팅 선언 변경(" + slot.name() + "): " + before.name() + " → " + after.name(),
        null,
        null,
        "SUCCESS",
        null,
        Map.of("slot", slot.name(), "from", before.name(), "to", after.name()));
  }
}
