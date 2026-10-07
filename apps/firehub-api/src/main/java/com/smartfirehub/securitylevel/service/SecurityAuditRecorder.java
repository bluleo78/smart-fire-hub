package com.smartfirehub.securitylevel.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 보안 등급 관리 작업의 감사 기록(스펙 §4.6 "항상" 항목). AuditLogService 는 호출자 트랜잭션에 합류하므로, 변경과 감사가 함께 커밋/롤백된다. */
@Component
@RequiredArgsConstructor
public class SecurityAuditRecorder {

  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  public void record(
      long actorUserId,
      String action,
      String resource,
      String resourceId,
      String description,
      Map<String, Object> metadata) {
    String actorName =
        userRepository.findById(actorUserId).map(UserResponse::username).orElse("unknown");
    auditLogService.log(
        actorUserId,
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
