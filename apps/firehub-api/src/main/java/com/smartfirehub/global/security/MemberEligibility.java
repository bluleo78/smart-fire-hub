package com.smartfirehub.global.security;

import com.smartfirehub.permission.repository.PermissionRepository;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 「다른 사람 이름으로 대신 도는 일」의 주인 자격 판정(WD-169, WD-166 에서 공통화).
 *
 * <p>스마트 작업(작업 주인 이름)과 파이프라인 트리거(트리거를 만든 사람 이름)는 사람이 없을 때 그 사람 이름으로 실행된다. 실행 직전에 그 사람이 여전히 이
 * 워크스페이스에서 그 일을 할 자격이 있는지 같은 순서로 본다 — 두 기능이 서로 다른 판정 순서를 갖지 않게 한 곳에 둔다.
 *
 * <p>순서가 곧 사유 구분이다: 계정 활성 → 이 워크스페이스 ACTIVE 멤버 → 권한. 권한
 * 조회(PermissionRepository.findPermissionCodesByUserId) 도 계정 활성·ACTIVE 멤버십을 함께 요구하지만, 그것만 쓰면 정지·제거된
 * 사람에게 「권한이 없다」는 엉뚱한 사유가 남으므로 앞의 두 검사를 따로 둔다.
 */
@Component
@RequiredArgsConstructor
public class MemberEligibility {

  /** 판정 결과. 호출자가 기능별 문구로 바꿔 쓴다. */
  public enum Status {
    /** 실행해도 된다. */
    ELIGIBLE,
    /** 계정 비활성(users.is_active=false)이거나, 이 워크스페이스에서 정지(SUSPENDED)·제거(멤버십 없음)됐다. */
    INACTIVE,
    /** 계정·멤버십은 정상인데 이 워크스페이스에서 요구 권한이 없다(역할 회수·권한 회수). */
    NO_PERMISSION
  }

  private final UserRepository userRepository;
  private final MembershipRepository membershipRepository;
  // 권한 조회는 자체 읽기 트랜잭션을 열어 현재 테넌트 GUC 아래(user_role RLS)에서만 본다 — 다른 워크스페이스의 권한으로 통과하지 않는다
  private final PermissionChecker permissionChecker;
  // 배치 판정(checkAll) 용 — 사용자 단위 권한 조회와 같은 조인(activeMemberGrants)으로 「권한 가진 사람 전체」를 한 번에 받는다
  private final PermissionRepository permissionRepository;

  /**
   * 사용자가 지금 이 워크스페이스에서 권한 {@code permissionCode} 가 필요한 일을 할 자격이 있는지 본다. 실행 테넌트 컨텍스트 안에서 부른다.
   *
   * @param userId 대신 실행되는 사람(작업 주인·트리거를 만든 사람)
   * @param tenantId 실행 워크스페이스 — 테넌트 컨텍스트에서 호출자가 꺼내 넘긴다
   * @param permissionCode 요구 권한 코드
   */
  public Status check(Long userId, long tenantId, String permissionCode) {
    if (!isActiveMember(userId, tenantId)) {
      return Status.INACTIVE;
    }
    // 이 워크스페이스에서의 권한 — 역할 회수·권한 회수면 돌지 않는다
    if (!permissionChecker.hasPermission(userId, permissionCode)) {
      return Status.NO_PERMISSION;
    }
    return Status.ELIGIBLE;
  }

  /** 계정이 활성이고 이 워크스페이스의 ACTIVE 멤버인가. 알림 수신자 거르기(WD-169)에도 쓴다 — 정지·제거된 사람에게 그 워크스페이스 소식을 보내지 않게. */
  public boolean isActiveMember(Long userId, long tenantId) {
    // 1) 계정 활성 — 운영자가 전역 계정을 비활성화하면 어느 워크스페이스에서도 돌지 않는다
    boolean accountActive =
        userRepository.findById(userId).map(UserResponse::isActive).orElse(false);
    if (!accountActive) {
      return false;
    }
    // 2) 이 워크스페이스의 ACTIVE 멤버 — 멤버십 정지(SUSPENDED)·제거(행 삭제)면 아니다
    return membershipRepository.hasActiveMembership(userId, tenantId);
  }

  /**
   * 여러 사람의 자격을 한 번에 본다(WD-192) — 워크스페이스 전체 스마트 작업 목록처럼 주인이 여럿인 화면에서 사람마다 {@link #check} 를 부르면 N+1 이
   * 되기 때문이다.
   *
   * <p>판정 규칙과 순서는 {@link #check} 와 같다(계정 활성 → ACTIVE 멤버 → 권한). 계정 활성·멤버십은 {@code
   * MembershipRepository.findActiveMemberIds}(={@link #isActiveMember} 의 배치판), 권한은 {@code
   * PermissionRepository.findActiveMemberIdsWithPermission}(=권한 확인이 쓰는 {@code
   * findPermissionCodesByUserId} 와 같은 조인)로 본다. user_role 이 RLS 대상이라 테넌트 GUC 가 주입된 트랜잭션 안에서 불러야 한다.
   *
   * @return 사용자 id → 판정. 입력에 있던 id 는 모두 키로 나온다
   */
  public Map<Long, Status> checkAll(
      Collection<Long> userIds, long tenantId, String permissionCode) {
    Set<Long> distinct = new HashSet<>(userIds);
    Map<Long, Status> result = new HashMap<>();
    if (distinct.isEmpty()) {
      return result;
    }
    Set<Long> activeMembers = membershipRepository.findActiveMemberIds(tenantId, distinct);
    Set<Long> permitted =
        permissionRepository.findActiveMemberIdsWithPermission(tenantId, permissionCode);
    for (Long userId : distinct) {
      if (!activeMembers.contains(userId)) {
        result.put(userId, Status.INACTIVE);
      } else if (!permitted.contains(userId)) {
        result.put(userId, Status.NO_PERMISSION);
      } else {
        result.put(userId, Status.ELIGIBLE);
      }
    }
    return result;
  }
}
