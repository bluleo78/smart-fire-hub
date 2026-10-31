package com.smartfirehub.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.repository.AuditLogRepository;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.util.ClientIpExtractor;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@Slf4j
public class AuditLogService {

  private final AuditLogRepository auditLogRepository;
  private final ObjectMapper objectMapper;
  private final DatasetAccessGuard datasetAccessGuard;
  private final ClientIpExtractor clientIpExtractor;

  /**
   * DatasetAccessGuard 는 @Lazy 로 받는다 — 가드가 SecurityAuditRecorder 를 거쳐 이 서비스를 기록용으로 쓰므로 바로 받으면 생성자
   * 순환이 된다. 가드는 목록 조회(WD-289)에서만 쓴다.
   */
  public AuditLogService(
      AuditLogRepository auditLogRepository,
      ObjectMapper objectMapper,
      @Lazy DatasetAccessGuard datasetAccessGuard,
      ClientIpExtractor clientIpExtractor) {
    this.auditLogRepository = auditLogRepository;
    this.objectMapper = objectMapper;
    this.datasetAccessGuard = datasetAccessGuard;
    this.clientIpExtractor = clientIpExtractor;
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

    // 호출처가 IP·User-Agent 를 넘기지 않았으면 지금 처리 중인 HTTP 요청에서 채운다(WD-394) — 데이터셋·API 연결·역할·멤버·보안 등급
    // 감사 도우미 대부분이 null 을 넘겨, 로그인·임포트 밖의 사용자 행위는 IP 가 비어 있었다. 요청 밖(스케줄·비동기 잡)에서는 요청이 없어
    // 그대로 비운다 — 그때는 사람의 접속 주소가 없다.
    HttpServletRequest request = currentRequest();
    if (request != null) {
      if (ipAddress == null) {
        ipAddress = clientIpExtractor.extract(request);
      }
      if (userAgent == null) {
        userAgent = request.getHeader("User-Agent");
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

  /** 지금 스레드가 처리 중인 HTTP 요청(요청 밖 — 스케줄러·비동기 잡 — 이면 null). */
  private static HttpServletRequest currentRequest() {
    return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
        ? attrs.getRequest()
        : null;
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
