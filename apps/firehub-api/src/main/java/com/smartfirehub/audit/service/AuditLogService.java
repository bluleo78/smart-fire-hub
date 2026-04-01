package com.smartfirehub.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.repository.AuditLogRepository;
import com.smartfirehub.global.dto.PageResponse;
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

  @Transactional(readOnly = true)
  public PageResponse<AuditLogResponse> getAuditLogs(
      String search, String actionType, String resource, String result, int page, int size) {
    return auditLogRepository.findAll(search, actionType, resource, result, page, size);
  }
}
