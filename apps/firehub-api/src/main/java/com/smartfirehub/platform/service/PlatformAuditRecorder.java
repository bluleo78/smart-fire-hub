package com.smartfirehub.platform.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 운영자 평면 감사 기록(WD-12) — 계정 조치·테넌트 생명주기가 같은 형태(metadata.plane=platform)로 남게 한 곳에 모은다.
 *
 * <p>tenant_id 는 DEFAULT(GUC)로 NULL 이 된다: 운영자 요청은 TenantContext 를 세우지 않아
 * TenantAwareTransactionManager 가 GUC 를 심지 않고, V99 WITH CHECK({@code tenant_id IS NOT DISTINCT FROM
 * GUC})가 NULL=NULL 로 통과한다. 호출자 트랜잭션에 합류(REQUIRED)한다 — 조치와 감사가 함께 커밋되거나 함께 롤백된다("기록 없는 조치" 를 남기지
 * 않는다).
 */
@Component
@RequiredArgsConstructor
public class PlatformAuditRecorder {

  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  /**
   * 운영자 조치 한 건을 플랫폼 감사로 남긴다.
   *
   * @param details 액션별 대상 정보(예: targetUsername, tenantSlug·tenantName) — plane 뒤에 덧붙는다
   */
  public void record(
      long operatorId,
      String action,
      String resource,
      String resourceId,
      String description,
      Map<String, Object> details) {
    // 행위자 이름은 감사 화면의 "행위자" 칸 — 계정이 지워졌으면 unknown 으로 남긴다(기존 계정 조치와 같은 규칙).
    String operatorName =
        userRepository.findById(operatorId).map(UserResponse::username).orElse("unknown");
    // plane 을 맨 앞에 둔다 — 플랫폼 감사 화면·필터가 이 표식으로 운영자 평면 기록을 구분한다.
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("plane", "platform");
    metadata.putAll(details);
    auditLogService.log(
        operatorId,
        operatorName,
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
