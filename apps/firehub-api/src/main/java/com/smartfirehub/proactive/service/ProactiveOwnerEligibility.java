package com.smartfirehub.proactive.service;

import com.smartfirehub.global.security.MemberEligibility;
import com.smartfirehub.global.tenant.TenantContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 스마트 작업 실행 직전 작업 주인 자격 재확인(WD-166).
 *
 * <p>스마트 작업은 만든 사람(주인) 이름으로 예약·이상 탐지·지금 실행 때마다 돈다. 예전에는 실행 시점에 주인을 다시 보지 않아, 주인이 정지되거나 워크스페이스에서
 * 제거되거나 「스마트 작업」 권한을 잃은 뒤에도 그 사람 이름으로 AI 리포트가 계속 만들어져 메일·앱 알림으로 나갔다. 파이프라인 트리거의 실행 시점 권한 재확인
 * (WD-167, {@code TriggerService} 의 {@code pipeline:execute} 확인)과 같은 방식이다.
 *
 * <p>작업 자체(enabled·스케줄 등록)는 건드리지 않는다 — 주인을 다시 활성화·재초대·권한 복구하면 다음 실행부터 저절로 다시 돈다.
 */
@Component
@RequiredArgsConstructor
public class ProactiveOwnerEligibility {

  /** 작업 주인 계정이 비활성이거나, 실행 워크스페이스의 ACTIVE 멤버가 아니어서 건너뛴 사유(WD-166). */
  public static final String REASON_OWNER_INACTIVE = "작업 주인이 정지됐거나 워크스페이스에서 제거돼 실행하지 않았습니다";

  /** 작업 주인에게 실행 워크스페이스의 스마트 작업 쓰기 권한(proactive:write)이 없어 건너뛴 사유(WD-166). */
  public static final String REASON_OWNER_NO_PERMISSION = "작업 주인에게 스마트 작업 권한이 없어 실행하지 않았습니다";

  /**
   * 화면용 사유 코드(WD-192) — 작업 응답의 {@code ownerIneligibleReason}. 실행 이력 문구(위 REASON_*)와 같은 판정에서 나오지만,
   * 웹이 문구를 직접 고르도록 기계 코드로 준다. 계정 비활성·멤버십 정지·워크스페이스 제거.
   */
  public static final String CODE_OWNER_INACTIVE = "OWNER_INACTIVE";

  /** 화면용 사유 코드(WD-192) — 작업 주인에게 이 워크스페이스의 proactive:write 가 없다(역할·권한 회수). */
  public static final String CODE_OWNER_NO_PERMISSION = "OWNER_NO_PERMISSION";

  /** 스마트 작업을 만들고 고칠 수 있는 권한 — 작업 생성·수정·지금 실행 API 가 요구하는 것과 같다. */
  static final String REQUIRED_PERMISSION = "proactive:write";

  // 판정 순서(계정 활성 → ACTIVE 멤버 → 권한)는 트리거와 공유하는 공용 판정을 쓴다(WD-169)
  private final MemberEligibility memberEligibility;

  /**
   * 작업 주인이 지금 이 워크스페이스에서 작업을 돌릴 자격이 있는지 본다. 실행 테넌트 컨텍스트 안에서 부른다.
   *
   * <p>순서가 곧 사유 구분이다: 계정 활성 → 이 워크스페이스 ACTIVE 멤버 → proactive:write. 권한 조회도 계정 활성·ACTIVE 멤버십을 함께
   * 요구하지만 (PermissionRepository.findPermissionCodesByUserId), 그것만 쓰면 정지·제거된 주인에게 「권한이 없다」는 엉뚱한 사유가
   * 남으므로 앞의 두 검사를 따로 둔다.
   *
   * @param ownerId 작업 주인(proactive_job.user_id)
   * @return 실행하면 안 되는 사유. 실행해도 되면 빈 값
   */
  public Optional<String> findIneligibilityReason(Long ownerId) {
    long tenantId = TenantContext.require("스마트 작업 주인 자격 확인");
    return switch (memberEligibility.check(ownerId, tenantId, REQUIRED_PERMISSION)) {
      // 계정 비활성·멤버십 정지·워크스페이스 제거
      case INACTIVE -> Optional.of(REASON_OWNER_INACTIVE);
      // 역할 회수·권한 회수
      case NO_PERMISSION -> Optional.of(REASON_OWNER_NO_PERMISSION);
      case ELIGIBLE -> Optional.empty();
    };
  }

  /**
   * 작업 주인이 지금 실행 자격이 있는지 화면용 사유 코드로 본다(WD-192). 실행기가 쓰는 {@link #findIneligibilityReason} 과 같은 판정이라
   * 목록·상세의 「실행되지 않음」 표시가 실제 실행 건너뜀과 어긋나지 않는다.
   *
   * @return 자격이 없으면 {@link #CODE_OWNER_INACTIVE}·{@link #CODE_OWNER_NO_PERMISSION}, 있으면 빈 값
   */
  public Optional<String> findIneligibilityCode(Long ownerId) {
    long tenantId = TenantContext.require("스마트 작업 주인 자격 표시");
    return codeOf(memberEligibility.check(ownerId, tenantId, REQUIRED_PERMISSION));
  }

  /**
   * 여러 작업 주인의 자격을 한 번에 본다(WD-192 — 워크스페이스 전체 목록의 N+1 방지). 판정 규칙은 {@link #findIneligibilityCode} 와
   * 같은 {@code MemberEligibility} 를 배치로 쓴다.
   *
   * @return 주인 id → 사유 코드(자격이 있으면 빈 값). 입력의 id 는 모두 키로 나온다
   */
  public Map<Long, Optional<String>> findIneligibilityCodes(Collection<Long> ownerIds) {
    long tenantId = TenantContext.require("스마트 작업 주인 자격 표시");
    Map<Long, Optional<String>> result = new HashMap<>();
    memberEligibility
        .checkAll(ownerIds, tenantId, REQUIRED_PERMISSION)
        .forEach((ownerId, status) -> result.put(ownerId, codeOf(status)));
    return result;
  }

  private static Optional<String> codeOf(MemberEligibility.Status status) {
    return switch (status) {
      case INACTIVE -> Optional.of(CODE_OWNER_INACTIVE);
      case NO_PERMISSION -> Optional.of(CODE_OWNER_NO_PERMISSION);
      case ELIGIBLE -> Optional.empty();
    };
  }
}
