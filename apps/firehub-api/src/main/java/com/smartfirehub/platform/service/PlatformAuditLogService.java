package com.smartfirehub.platform.service;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.repository.AuditLogRepository;
import com.smartfirehub.global.dto.PageResponse;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자 콘솔 플랫폼 감사 로그(WD-4) — 테넌트에 속하지 않는(tenant_id NULL) 감사 행만 조회한다.
 *
 * <p>범위를 NULL 행으로 제한하는 이유: 테넌트 행을 보여 주면 운영자에게 테넌트 데이터 열람을 여는 권한 상승이 된다. NULL 행에는 운영자 계정
 * 조치(ACCOUNT_DEACTIVATE/REACTIVATE)와 워크스페이스가 정해지지 않은 로그인이 남는다 — 후자는 어느 테넌트 감사 화면에도 보이지 않아 여기가 유일한
 * 열람 경로다.
 */
@Service
@RequiredArgsConstructor
public class PlatformAuditLogService {

  /** 한 페이지 최대 행 수. 0 은 페이지 수 계산의 0 나누기, 과대값은 한 요청 과부하라 막는다. */
  static final int MAX_PAGE_SIZE = 100;

  private final AuditLogRepository auditLogRepository;

  /**
   * 날짜는 벽시계 하루 단위다 — {@code action_time} 이 타임존 없는 TIMESTAMP 이고 화면이 그 문자열을 그대로 보이므로, {@code [from
   * 00:00, to+1 00:00)} 로 바꾸면 화면에 보이는 날짜와 조회 범위가 정확히 같다.
   */
  @Transactional(readOnly = true)
  public PageResponse<AuditLogResponse> search(
      String actor,
      String target,
      String actionType,
      LocalDate from,
      LocalDate to,
      int page,
      int size) {
    if (page < 0) {
      throw new IllegalArgumentException("page 는 0 이상이어야 합니다");
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("size 는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다");
    }
    if (from != null && to != null && from.isAfter(to)) {
      throw new IllegalArgumentException("시작일이 종료일보다 늦습니다");
    }
    return auditLogRepository.findPlatform(
        actor,
        target,
        actionType,
        from == null ? null : from.atStartOfDay(),
        to == null ? null : to.plusDays(1).atStartOfDay(),
        page,
        size);
  }
}
