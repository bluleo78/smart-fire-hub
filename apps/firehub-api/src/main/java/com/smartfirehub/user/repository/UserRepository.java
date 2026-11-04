package com.smartfirehub.user.repository;

import com.smartfirehub.user.dto.UserResponse;
import java.util.List;
import java.util.Optional;

/**
 * 사용자(user 테이블)와 역할 할당(user_role)·테넌트 멤버십(membership) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqUserRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface UserRepository {

  Optional<UserResponse> findByUsername(String username);

  Optional<UserResponse> findById(Long id);

  /**
   * 로그인 입력을 저장된 username 으로 해석한다(WD-269). 찾지 못하면 소문자로 정규화한 입력을 돌려준다.
   *
   * <p>멤버 추가·운영자 계정 생성은 이메일을 소문자로 바꿔 username 으로 저장하는데, 사용자는 안내받은 대소문자 그대로 치거나 모바일이 첫 글자를 대문자로 바꾼다.
   * 그래서 정확 일치를 먼저 보고(대소문자를 섞어 저장된 옛 계정 — 첫 가입자 등 — 은 그대로 로그인돼야 한다. 인덱스를 타는 조회라 대부분의 로그인은 여기서 끝난다),
   * 없으면 {@link #findByUsernameIgnoreCase} 로 찾는다. 대소문자만 다른 계정이 여럿인 과거 데이터에서 멤버 추가와 <b>같은 계정</b>(id 가
   * 작은 쪽)을 골라야, 멤버십이 붙은 계정과 로그인되는 계정이 갈리지 않는다.
   *
   * <p>찾지 못했을 때도 정규화한 값을 돌려주는 이유: 호출자가 로그인 시도 카운터 키로 쓴다. 입력 그대로 쓰면 대소문자만 바꿔 가며 잠금을 피할 수 있다.
   */
  String resolveLoginUsername(String input);

  Optional<String> findPasswordByUsername(String username);

  Optional<String> findPasswordById(Long id);

  boolean existsByUsername(String username);

  /**
   * 사용자가 한 명이라도 있는지 (테넌트 무관, 전역).
   *
   * <p>회원가입의 "첫 사용자에게 ADMIN 부여" 판정에만 쓴다. 예전에는 {@code countAll(null) == 0} 이었는데, {@code countAll} 이
   * 현재 테넌트 소속으로 좁혀지면서 그 판정이 "이 테넌트의 첫 멤버" 로 바뀌어 버린다 — 부트스트랩 의미(시스템 최초 사용자)와 다르다. 전역이라는 사실을 이름으로 드러내
   * 분리한다.
   */
  boolean existsAnyUser();

  /**
   * Acquire a transaction-scoped advisory lock for first-user detection. Serializes concurrent
   * signup requests that might race for ADMIN role assignment. The lock is automatically released
   * when the transaction ends.
   */
  void acquireFirstUserLock();

  /**
   * 테넌트 단위 "마지막 활성 ADMIN" 판정 직렬화 잠금(트랜잭션 범위, WD-2 리뷰 지적 3).
   *
   * <p>두 관리자가 서로를 동시에 정지·제거하면 각자 "활성 ADMIN 2명" 을 보고 통과해 0 명이 될 수 있다. 판정 전에 이 잠금을 잡으면 두 번째 트랜잭션은 첫
   * 번째 커밋 뒤의 수를 본다(READ COMMITTED). 키는 접두어를 붙인 문자열 해시다 — 가입 잠금({@link #acquireFirstUserLock} 의 키
   * 1)과 같은 숫자 키 공간을 쓰지만 테넌트 id 를 그대로 키로 쓰면 테넌트 1 이 가입 잠금과 충돌하므로 네임스페이스를 나눈다.
   */
  void acquireMemberAdminGuardLock(long tenantId);

  UserResponse save(String username, String email, String password, String name);

  /**
   * username 대소문자 무시 조회 — 멤버 추가 시 "이미 가입된 계정인가" 판정용.
   *
   * <p>username 은 이메일이고 사람마다 대소문자를 섞어 입력한다. 정확 일치만 보면 같은 사람의 두 번째 계정이 생긴다. 같은 값이 대소문자만 달리 두 행 있는 과거
   * 데이터는 id 가 작은 쪽을 쓴다.
   */
  Optional<UserResponse> findByUsernameIgnoreCase(String username);

  /** 관리자가 임시 비밀번호로 만드는 계정 — 첫 로그인 시 변경 강제 표식을 켠 채 저장한다. */
  UserResponse saveWithTemporaryPassword(
      String username, String email, String encodedPassword, String name);

  /**
   * 사용자 목록 — <b>현재 테넌트의 멤버(ACTIVE·SUSPENDED 모두)</b>. 정지 멤버도 보여야 재활성·제거할 수 있다.
   *
   * <p>tenantId 를 인자로 받는 이유: 이 코드베이스의 리포지토리는 {@code TenantContext} 를 직접 읽지 않는다({@code
   * MembershipRepository} 도 인자로 받는다). ThreadLocal 과 트랜잭션 GUC 가 어긋날 수 있는 경로가 실제로 존재하므로({@code
   * CurrentTransactionTenant} 참고), 테넌트 해석은 트랜잭션 경계를 아는 서비스 레이어에서 한 번만 한다.
   */
  List<UserResponse> findAllPaginated(long tenantId, String search, int page, int size);

  /**
   * 주어진 ID들에 해당하는 사용자를 현재 테넌트 멤버로 한정하여 조회한다 (#555).
   *
   * <p>알림 수신자 등 이미 ID로 저장된 값을 이름/이메일로 되살릴 때(하이드레이션) 사용 — {@link #findAllPaginated}는 검색어 기반이라 검색을
   * 거치지 않은 기존 선택값은 찾지 못한다.
   */
  List<UserResponse> findByIds(long tenantId, List<Long> ids);

  /** {@link #findAllPaginated} 의 총건수. 같은 테넌트 술어를 반드시 함께 적용해야 페이지가 맞는다. */
  long countAll(long tenantId, String search);

  /** 이름·프로필 이메일을 바꾼다. 해당 사용자가 없어 바뀐 행이 없으면 false — 존재 확인을 따로 조회하지 않으려고 결과로 돌려준다. */
  boolean update(Long id, String name, String email);

  /** 비밀번호를 바꾸고, 임시 비밀번호 표식도 내린다(본인 변경이 유일한 호출처). */
  void updatePassword(Long id, String encodedPassword);

  /**
   * 전역 계정 활성 플래그 — 운영자 콘솔 계정 비활성화/재활성화(#784, PlatformAccountService)만 쓴다. 테넌트 정지는 멤버십.
   *
   * <p>이미 그 값이면 바꾸지 않고 0 을 돌려준다. 동시 요청 중 실제로 바꾼 쪽만 1 을 받는다 — 뒤 요청은 행 잠금을 기다린 뒤 새 값으로 조건을 다시
   * 평가한다(WD-322). 호출자는 0 이면 감사·세션 폐기를 하지 않는다.
   */
  int setActive(Long id, boolean active);

  /**
   * 이 테넌트의 "활성 ADMIN" 수 — 잠금 방지(마지막 ADMIN 정지·제거 금지) 판정용 (#146, WD-2).
   *
   * <p>활성 = 이 테넌트 멤버십 ACTIVE + 이 테넌트 ADMIN 역할 + 전역 계정 활성. 예전 구현은 전역 {@code user.is_active} 만 봐서, 이
   * 테넌트에서 정지된 ADMIN 도 "활성" 으로 세어 마지막 활성 ADMIN 정지를 허용했다. RLS 에 기대지 않고 tenant_id 를 명시하는 이유:
   * membership 은 RLS 가 없다.
   */
  int countActiveAdmins(long tenantId);

  /**
   * 이 테넌트의 "활성 ADMIN" 사용자 id 목록(WD-169). 트리거가 정지·권한 회수로 건너뛰었을 때 알릴 워크스페이스 관리자를 고르는 데 쓴다. 정의는 {@link
   * #countActiveAdmins} 와 같다 — 멤버십 ACTIVE + 이 테넌트 ADMIN 역할 + 전역 계정 활성. membership.role(OWNER/ADMIN
   * 라벨)은 표시용이라 쓰지 않는다.
   */
  List<Long> findActiveAdminIds(long tenantId);

  /** 이 테넌트에서 사용자의 역할 배정을 모두 지운다(멤버 제거). RLS 와 별개로 tenant_id 를 명시한다. */
  int deleteRolesInTenant(Long userId, long tenantId);

  /**
   * 특정 사용자가 ADMIN 역할을 갖고 있는지 확인한다. 마지막 ADMIN 비활성화 방지 체크에 사용 (#146).
   *
   * @param userId 확인할 사용자 ID
   * @return 해당 사용자가 ADMIN 역할을 보유하면 true
   */
  boolean hasAdminRole(Long userId);

  void addRole(Long userId, Long roleId);

  void removeRole(Long userId, Long roleId);

  void setRoles(Long userId, List<Long> roleIds);
}
