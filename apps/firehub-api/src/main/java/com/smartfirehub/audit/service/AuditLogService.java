package com.smartfirehub.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.repository.AuditLogRepository;
import com.smartfirehub.global.dto.PageResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

  private final AuditLogRepository auditLogRepository;
  private final ObjectMapper objectMapper;

  public Long log(
      Long userId,
      String username,
      String actionType,
      String resource,
      String resourceId,
      String description,
      String ipAddress,
      String userAgent,
      String result,
      String errorMessage,
      Object metadata) {
    JSONB metadataJsonb = null;
    if (metadata != null) {
      try {
        metadataJsonb = JSONB.jsonb(objectMapper.writeValueAsString(metadata));
      } catch (Exception e) {
        log.warn("Failed to serialize audit metadata", e);
      }
    }

    // 컬럼 길이를 넘는 값은 잘라 넣는다(WD-290) — 인증 없이 들어오는 값(로그인 실패의 아이디, X-Forwarded-For)도 감사에 실리므로,
    // 길이 초과로 INSERT 가 실패해 요청 자체가 500 으로 바뀌지 않게 한다.
    return auditLogRepository.save(
        userId,
        truncate(username, USERNAME_MAX),
        actionType,
        resource,
        truncate(resourceId, RESOURCE_ID_MAX),
        description,
        truncate(ipAddress, IP_ADDRESS_MAX),
        userAgent,
        result,
        errorMessage,
        metadataJsonb);
  }

  /** audit_log.username 길이(VARCHAR(50)). */
  public static final int USERNAME_MAX = 50;

  /** audit_log.resource_id 길이(VARCHAR(100)). */
  private static final int RESOURCE_ID_MAX = 100;

  /** audit_log.ip_address 길이(VARCHAR(45)). */
  private static final int IP_ADDRESS_MAX = 45;

  /** 감사 컬럼 길이에 맞춰 자른다(null 은 그대로). */
  public static String truncate(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }

  @Transactional(readOnly = true)
  public List<AuditLogResponse> findByResource(
      String actionType, String resource, String resourceId) {
    return auditLogRepository.findByResource(actionType, resource, resourceId);
  }

  @Transactional(readOnly = true)
  public Optional<AuditLogResponse> findById(Long id) {
    return auditLogRepository.findById(id);
  }

  /**
   * 감사 로그 목록 페이지 조회
   *
   * @param userId 특정 사용자 ID 정확 일치 필터 (null이면 무제한, #89)
   * @param startDate 날짜 범위 시작 (null이면 무제한)
   * @param endDate 날짜 범위 종료 (null이면 무제한)
   */
  @Transactional(readOnly = true)
  public PageResponse<AuditLogResponse> getAuditLogs(
      String search,
      Long userId,
      String actionType,
      String resource,
      String result,
      LocalDateTime startDate,
      LocalDateTime endDate,
      int page,
      int size) {
    return auditLogRepository.findAll(
        search, userId, actionType, resource, result, startDate, endDate, page, size);
  }
}
