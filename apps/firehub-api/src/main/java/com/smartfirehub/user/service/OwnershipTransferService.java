package com.smartfirehub.user.service;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.validation.TextRules;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.tenant.dto.TenantMembership;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.tenant.repository.TenantRepository;
import com.smartfirehub.user.dto.OwnershipTransferResult;
import com.smartfirehub.user.exception.UserNotFoundException;
import com.smartfirehub.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워크스페이스 소유권 이전(WD-1132).
 *
 * <p>웹(현재 소유자 본인)과 콘솔(운영자)이 같은 규칙으로 쓰는 공용 서비스다. 호출자 신원·감사에 의존하지 않고 테넌트 id 를 명시로 받는다 — 호출자 검사(403·자기
 * 자신 400)와 감사 기록은 각 진입점이 한다. 호출 시 {@code TenantContext} 가 그 테넌트로 잡혀 있어야 한다(역할 테이블이 RLS 대상이라서).
 *
 * <p>불변식: 이전 뒤 OWNER 는 언제나 대상 한 명뿐이다(대상 외 모든 OWNER 행을 ADMIN 으로 내린다). 새 소유자와 내려간 옛 소유자 모두 ADMIN 역할이
 * 없으면 부여한다 — OWNER 라벨은 표시용이고 실제 권한은 역할이 정하므로, 소유자가 관리 화면을 잃지 않게 한다.
 */
@Service
@RequiredArgsConstructor
public class OwnershipTransferService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final MembershipRepository membershipRepository;
  private final TenantRepository tenantRepository;

  /**
   * 대상을 그 테넌트의 유일한 소유자로 만든다.
   *
   * <p>오류: 확인 이름 불일치 400({@link IllegalArgumentException}), 워크스페이스 없음·대상이 이 테넌트 멤버 아님 404, 정지·계정
   * 비활성 409, 이미 유일한 소유자 409 ({@link IllegalStateException}).
   *
   * <p>정지·제거·역할 변경 가드와 같은 테넌트 잠금 아래에서 한 트랜잭션으로 판정하고 쓴다 — 이전과 동시에 소유자를 정지하거나 ADMIN 을 빼는 요청이 낡은 소유자
   * 상태를 보고 통과하지 못하게 한다.
   */
  @Transactional
  public OwnershipTransferResult transfer(long tenantId, Long targetUserId, String confirmName) {
    userRepository.acquireMemberAdminGuardLock(tenantId);

    // 확인 이름: 저장 이름·입력 모두 앞뒤 공백(TextRules.strip — NBSP·전각 공백 포함, 웹 stripEdgeSpace 와 같은 유니코드
    // White_Space 기준)을 무시하고 비교, 대소문자는 구분(오타 방지용 확인이라 느슨하게 받지 않는다).
    // 저장 이름이 " Acme " 처럼 공백을 가져도 사용자가 공백 없이 입력해 통과할 수 있어야 한다(WD-1132 리뷰) — 한쪽만 trim 하면 영영 실패한다.
    // 워크스페이스가 없으면 409(상태 충돌)가 아니라 404 다 — 다른 없는 리소스와 같은 관례.
    String tenantName =
        tenantRepository
            .findName(tenantId)
            .orElseThrow(
                () ->
                    new CodedApiException(
                        HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "워크스페이스를 찾을 수 없습니다"));
    if (confirmName == null || !TextRules.strip(tenantName).equals(TextRules.strip(confirmName))) {
      throw new IllegalArgumentException("워크스페이스 이름이 일치하지 않습니다");
    }

    // 다른 테넌트 사용자는 이 테넌트 입장에서 없는 것(계정 열거 방지) → 404.
    TenantMembership target =
        membershipRepository
            .findInTenant(targetUserId, tenantId)
            .orElseThrow(UserNotFoundException::notInWorkspace);
    boolean accountActive =
        userRepository.findById(targetUserId).map(u -> u.isActive()).orElse(false);
    if (!target.isActive() || !accountActive) {
      throw new IllegalStateException("정지되었거나 비활성화된 멤버에게는 소유권을 넘길 수 없습니다");
    }

    List<Long> owners = membershipRepository.findOwnerUserIds(tenantId);
    if (owners.size() == 1 && owners.get(0).equals(targetUserId)) {
      throw new IllegalStateException("이미 이 워크스페이스의 소유자입니다");
    }
    List<Long> fromUserIds = owners.stream().filter(id -> !id.equals(targetUserId)).toList();

    membershipRepository.demoteOwnersExcept(tenantId, targetUserId);
    membershipRepository.updateRole(targetUserId, tenantId, "OWNER");

    // ADMIN 역할 보장 — 새 소유자와 내려간 옛 소유자 모두.
    // ADMIN 역할이 없으면 프로비저닝이 손상된 테넌트다 — 조용히 건너뛰면 소유자가 관리 화면을 잃으므로 트랜잭션째 실패시킨다.
    Long adminRoleId =
        roleRepository
            .findByName("ADMIN")
            .map(RoleResponse::id)
            .orElseThrow(() -> new IllegalStateException("이 워크스페이스에 ADMIN 역할이 없어 소유권을 넘길 수 없습니다"));
    // 실제로 ADMIN 을 새로 받은 사용자 — 호출자가 ROLE_ASSIGN 감사를 남긴다. 새 소유자를 먼저, 이어서 내려간 옛 소유자들.
    // 판정은 EXISTS 쿼리(hasAdminRole) 한 번이라 역할 목록을 통째로 읽지 않는다.
    List<Long> grantedAdminUserIds = new ArrayList<>();
    List<Long> candidates = new ArrayList<>();
    candidates.add(targetUserId);
    candidates.addAll(fromUserIds);
    for (Long userId : candidates) {
      if (!userRepository.hasAdminRole(userId)) {
        userRepository.addRole(userId, adminRoleId);
        grantedAdminUserIds.add(userId);
      }
    }
    return new OwnershipTransferResult(fromUserIds, targetUserId, List.copyOf(grantedAdminUserIds));
  }
}
