package com.smartfirehub.proactive.service;

import com.smartfirehub.global.security.MemberEligibility;
import com.smartfirehub.global.tenant.TenantContext;
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
}
