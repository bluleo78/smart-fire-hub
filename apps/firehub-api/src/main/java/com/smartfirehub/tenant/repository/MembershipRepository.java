package com.smartfirehub.tenant.repository;

import com.smartfirehub.tenant.dto.MembershipResponse;
import com.smartfirehub.tenant.dto.TenantMembership;
import com.smartfirehub.tenant.dto.UnavailableWorkspace;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 테넌트 멤버십(membership 테이블) 저장·조회 리포지토리. 소속 워크스페이스는 tenant 테이블과 조인해 읽는다.
 *
 * <p>구현은 {@code JooqMembershipRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface MembershipRepository {

  /**
   * 회원가입 시 자동 가입되는 기본 테넌트. V81 마이그레이션에서 시드된 tenant.id=1(slug=default).
   *
   * <p>자가 가입 사용자가 합류하는 기본 워크스페이스. 회원가입의 역할 조회도 이 테넌트 컨텍스트에서 일어난다({@code AuthService.signup} 참고) —
   * public 으로 승격해 트랜잭션 밖에서 미리 세팅할 수 있게 한다.
   */
  long DEFAULT_TENANT_ID = 1L;

  /** 사용자가 선택할 수 있는 테넌트 목록. 멤버십과 테넌트가 모두 ACTIVE 인 것만 반환한다. */
  List<MembershipResponse> findActiveByUser(Long userId);

  /**
   * 멤버십 행은 있지만 지금 들어갈 수 없는 워크스페이스 목록(WD-281). {@link #findActiveByUser} 의 여집합이다.
   *
   * <p>왜: 선택 가능 목록에서 정지된 것이 조용히 빠지면 웹은 「초대받은 적 없음」과 구분하지 못한다. 로그인 응답에 사유를 함께 실어 선택 화면이 누구에게 문의할지
   * 안내하게 한다.
   *
   * <p>사유: 테넌트가 ACTIVE 가 아니면 멤버십 상태와 무관하게 {@code WORKSPACE_SUSPENDED} — 멤버십을 되살려도 들어갈 수 없으니 운영자가
   * 풀어야 한다. 그 외(테넌트 ACTIVE, 멤버십 비ACTIVE)는 {@code MEMBERSHIP_SUSPENDED}. 제거된 멤버는 행이 삭제되므로({@link
   * #delete}) 자연히 포함되지 않는다.
   *
   * <p>RLS: {@link #findActiveByUser} 와 같이 전역 테이블만 읽어 테넌트 선택 전(로그인)에도 그대로 동작한다.
   */
  List<UnavailableWorkspace> findUnavailableByUser(Long userId);

  /** 해당 사용자가 그 테넌트의 ACTIVE 멤버이고 테넌트도 ACTIVE 인지. 테넌트 선택/갱신 시의 게이트. */
  boolean hasActiveMembership(Long userId, Long tenantId);

  /**
   * 회원가입한 사용자를 기본 테넌트(id=1)에 ACTIVE MEMBER 로 가입시킨다.
   *
   * <p>왜: 멤버십이 하나도 없는 사용자는 어떤 테넌트도 선택할 수 없어 테넌트 미선택 토큰만 발급받고, RLS 가 모든 행을 막아 모든 API 에서 403 을
   * 받는다(잠김). 자가 가입 사용자를 잠그지 않기 위해 가입과 동시에 기본 워크스페이스에 합류시킨다. 운영자가 다른 테넌트로 프로비저닝하는 것은 이후
   * 단계(operator-driven provisioning)에서 다룬다 — 이 메서드는 그 대상이 아니다.
   */
  void createDefaultMembership(Long userId);

  /** 이 테넌트에서의 멤버십(ACTIVE·SUSPENDED 모두). tenant.status 는 보지 않는다(관리 경로). */
  Optional<TenantMembership> findInTenant(Long userId, long tenantId);

  /** 목록 화면용 배치 조회 — 사용자마다 조회하면 페이지 크기만큼 N+1 이 된다. */
  Map<Long, TenantMembership> findInTenant(long tenantId, Collection<Long> userIds);

  /**
   * 주어진 사용자 중 「계정 활성 + 이 테넌트 ACTIVE 멤버 + 테넌트 ACTIVE」인 사람의 id(WD-192).
   *
   * <p>{@code MemberEligibility.isActiveMember} 의 사용자 단위 판정(계정 활성 → {@link #hasActiveMembership})을
   * 목록 화면에서 한 번의 질의로 하기 위한 배치판이다 — 워크스페이스 전체 스마트 작업 목록이 작업 주인마다 따로 조회하면 N+1 이 된다. 조건을 바꿀 때는 두 곳을 함께
   * 바꾼다. membership·tenant·user 모두 전역 테이블이라 tenant_id 를 직접 건다.
   */
  Set<Long> findActiveMemberIds(long tenantId, Collection<Long> userIds);

  /** 멤버 추가: 이 테넌트의 ACTIVE MEMBER 로 넣는다. 중복은 호출자가 먼저 걸러 409 로 응답한다. */
  void insertMember(Long userId, long tenantId);

  /** 정지/재활성. 이 테넌트 행만 바꾼다. 반환값은 바뀐 행 수(0 이면 비멤버). */
  int updateStatus(Long userId, long tenantId, String status);

  /** 테넌트에서 제거. 계정·다른 테넌트 멤버십은 그대로다. */
  int delete(Long userId, long tenantId);
}
