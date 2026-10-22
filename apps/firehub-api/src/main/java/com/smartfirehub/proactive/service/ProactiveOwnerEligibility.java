package com.smartfirehub.proactive.service;

import com.smartfirehub.global.security.PermissionChecker;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
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

  private final UserRepository userRepository;
  private final MembershipRepository membershipRepository;
  // 권한 조회는 자체 읽기 트랜잭션을 열어 현재 테넌트 GUC 아래(user_role RLS)에서만 본다 — 다른 워크스페이스의 권한으로 통과하지 않는다
  private final PermissionChecker permissionChecker;

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

    // 1) 계정 활성 — 운영자가 전역 계정을 비활성화(users.is_active=false)하면 어느 워크스페이스에서도 돌지 않는다
    boolean accountActive =
        userRepository.findById(ownerId).map(UserResponse::isActive).orElse(false);
    if (!accountActive) {
      return Optional.of(REASON_OWNER_INACTIVE);
    }
    // 2) 이 작업 워크스페이스의 ACTIVE 멤버 — 멤버십 정지(SUSPENDED)·제거(행 삭제)면 돌지 않는다
    if (!membershipRepository.hasActiveMembership(ownerId, tenantId)) {
      return Optional.of(REASON_OWNER_INACTIVE);
    }
    // 3) 이 워크스페이스에서 스마트 작업 쓰기 권한 — 역할 회수·권한 회수면 돌지 않는다
    if (!permissionChecker.hasPermission(ownerId, REQUIRED_PERMISSION)) {
      return Optional.of(REASON_OWNER_NO_PERMISSION);
    }
    return Optional.empty();
  }
}
