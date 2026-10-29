package com.smartfirehub.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.repository.AuditLogRepository;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class AuditLogService {

  private final AuditLogRepository auditLogRepository;
  private final ObjectMapper objectMapper;
  private final DatasetAccessGuard datasetAccessGuard;

  /**
   * DatasetAccessGuard 는 @Lazy 로 받는다 — 가드가 SecurityAuditRecorder 를 거쳐 이 서비스를 기록용으로 쓰므로 바로 받으면 생성자
   * 순환이 된다. 가드는 목록 조회(WD-289)에서만 쓴다.
   */
  public AuditLogService(
      AuditLogRepository auditLogRepository,
      ObjectMapper objectMapper,
      @Lazy DatasetAccessGuard datasetAccessGuard) {
    this.auditLogRepository = auditLogRepository;
    this.objectMapper = objectMapper;
    this.datasetAccessGuard = datasetAccessGuard;
  }

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

    return auditLogRepository.save(
        userId,
        username,
        actionType,
        resource,
        resourceId,
        description,
        ipAddress,
        userAgent,
        result,
        errorMessage,
        metadataJsonb);
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
   * <p>ADMIN 이 아니면(또는 AI 대행 조회면) 볼 수 없는 데이터셋(등급·허용 목록)의 행과 어느 데이터셋인지 특정할 수 없는 행(삭제됨·id 없음)을
   * 뺀다(WD-289) — audit:read 만으로 기밀 데이터셋의 이름·테이블명·접근자를 알아내지 못하게 한다. 판정은 데이터셋 목록과 같은 {@link
   * DatasetAccessGuard#visibleCondition(Clearance)} 다.
   *
   * @param viewer 조회하는 사용자의 열람 자격
   * @param userId 특정 사용자 ID 정확 일치 필터 (null이면 무제한, #89)
   * @param startDate 날짜 범위 시작 (null이면 무제한)
   * @param endDate 날짜 범위 종료 (null이면 무제한)
   */
  @Transactional(readOnly = true)
  public PageResponse<AuditLogResponse> getAuditLogs(
      Clearance viewer,
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
        search,
        userId,
        actionType,
        resource,
        result,
        startDate,
        endDate,
        page,
        size,
        // ADMIN 은 삭제된 데이터셋까지 모두 본다. 다만 AI 대행 조회면 설명의 데이터셋 이름이 LLM 으로 가므로 ADMIN 도 같은 판정(등급 AI
        // 정책 포함)을 받는다 — 다른 가시성 호출처와 같은 규칙(스펙 §4.3).
        viewer.tenantAdmin() && !datasetAccessGuard.inAiCall()
            ? null
            : datasetAccessGuard.visibleCondition(viewer));
  }
}
