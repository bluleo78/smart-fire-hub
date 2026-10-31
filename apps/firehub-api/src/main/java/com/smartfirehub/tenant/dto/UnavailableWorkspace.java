package com.smartfirehub.tenant.dto;

/**
 * 사용자가 멤버로 남아 있지만 지금은 들어갈 수 없는 워크스페이스 한 건(WD-281).
 *
 * <p>왜 필요한가: 선택 가능 목록({@link MembershipResponse})은 멤버십·테넌트가 모두 ACTIVE 인 것만 담는다. 정지된 것을 목록에서 빼기만 하면
 * 웹은 「초대받은 적 없음」과 「정지됨」을 구분할 수 없어, 정지된 사용자가 엉뚱하게 초대를 요청하게 된다. 그래서 정지된 것만 따로 이유와 함께 알린다.
 *
 * <p>테넌트 id 는 싣지 않는다 — 선택할 수 없는 워크스페이스라 클라이언트가 id 로 할 수 있는 일이 없고, 안내 문구에는 이름과 사유면 충분하다.
 *
 * @param tenantName 워크스페이스 이름(안내 문구에 표시)
 * @param reason 들어갈 수 없는 사유
 */
public record UnavailableWorkspace(String tenantName, Reason reason) {

  /** 들어갈 수 없는 사유. 사유마다 사용자가 문의할 상대가 다르다. */
  public enum Reason {
    /** 워크스페이스는 운영 중이지만 이 사용자의 멤버십이 정지됨 → 워크스페이스 관리자에게 문의. */
    MEMBERSHIP_SUSPENDED,
    /** 워크스페이스(테넌트) 자체가 정지됨 → 플랫폼 운영자에게 문의. 멤버십 상태와 무관하게 이 사유가 우선한다. */
    WORKSPACE_SUSPENDED
  }
}
