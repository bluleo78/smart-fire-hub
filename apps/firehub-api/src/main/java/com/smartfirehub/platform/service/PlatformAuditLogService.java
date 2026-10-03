package com.smartfirehub.platform.service;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.repository.AuditLogRepository;
import com.smartfirehub.audit.time.AuditTimes;
import com.smartfirehub.global.dto.PageResponse;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
   * 기간은 절대 시각이다 — 저장 TZ 는 서버만 알므로 운영자의 "하루" 는 admin 이 자기 로컬 자정 두 순간으로 보내고, 여기서 저장 벽시계로 바꿔 {@code
   * [from, to)} 로 비교한다(WD-11). 날짜만 받아 벽시계 하루로 해석하던 WD-4 방식은 운영(UTC 저장)에서 KST 운영자에게 9시간 어긋났다.
   */
  @Transactional(readOnly = true)
  public PageResponse<AuditLogResponse> search(
      String actor,
      String target,
      String actionType,
      OffsetDateTime from,
      OffsetDateTime to,
      int page,
      int size) {
    if (page < 0) {
      throw new IllegalArgumentException("page 는 0 이상이어야 합니다");
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("size 는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다");
    }
    if (from != null && to != null && !from.isBefore(to)) {
      throw new IllegalArgumentException("시작일이 종료일보다 늦습니다");
    }
    ZoneId storage = AuditTimes.storageZone();
    return auditLogRepository.findPlatform(
        actor,
        target,
        actionType,
        from == null ? null : AuditTimes.toStorage(from, storage),
        to == null ? null : AuditTimes.toStorage(to, storage),
        page,
        size);
  }
}
