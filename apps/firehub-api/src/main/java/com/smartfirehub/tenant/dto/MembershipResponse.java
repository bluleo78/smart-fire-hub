package com.smartfirehub.tenant.dto;

import java.util.List;
import java.util.Optional;

/**
 * 사용자가 선택할 수 있는 테넌트 한 건.
 *
 * @param role 표시용 라벨(OWNER/ADMIN/MEMBER). 인가 판단에는 쓰지 않는다
 */
public record MembershipResponse(
    Long tenantId, String tenantSlug, String tenantName, String role) {

  /**
   * ACTIVE 멤버십 목록에서 실행 테넌트를 해석하는 공통 규칙.
   *
   * <p>멤버십이 정확히 하나일 때만 그 테넌트가 모호하지 않다. 0개(멤버십 없음)이거나 2개 이상(어느
   * 워크스페이스인지 판별 불가)이면 빈 값을 돌려준다 — 모호할 때 어떻게 반응할지(로그 레벨, 기본값
   * 사용 여부, 실행 계속/중단)는 호출부마다 다른 정책이라 이 메서드가 정하지 않는다.
   *
   * <p><b>현재 호출부와 각자의 실패 정책(P2-f 기준)</b> — 위 문단이 "정책은 호출부 몫"이라고만
   * 말하고 실제 정책은 흩어져 있어, 다음 사람이 전수를 다시 찾아내지 않도록 여기 적어 둔다.
   *
   * <table>
   *   <caption>모호(0개 또는 2개 이상)할 때의 호출부별 반응</caption>
   *   <tr><th>호출부</th><th>로그</th><th>결과</th></tr>
   *   <tr>
   *     <td>{@code JwtAuthenticationFilter.resolveInternalTenant}</td>
   *     <td>WARN</td>
   *     <td>테넌트 컨텍스트 미설정 → 권한 0개 → <b>403</b>(fail-closed). {@code audit_log} 정책이
   *         형태 (b) 라 GUC 가 비면 모든 테넌트의 NULL 테넌트 LOGIN 행이 매칭되는 fail-open 이
   *         되는데, 이 고유 제약 때문에 여기만 유독 강하게 닫는다.</td>
   *   </tr>
   *   <tr>
   *     <td>{@code AuthService.login}</td>
   *     <td>없음</td>
   *     <td>테넌트 미선택 토큰 → <b>정상 200</b>. 여기서 "모호"는 오류가 아니라 테넌트 선택 화면으로
   *         가는 정상 UX 분기라 로그조차 남기지 않는 것이 옳다.</td>
   *   </tr>
   * </table>
   *
   * <p><b>통합하지 않기로 판단했다(P2-f, R10).</b> P2-e 에는 {@code ChatChannel} 이 세 번째
   * 호출부였으나, outbox 행이 {@code tenant_id} 를 싣게 되면서 그 임시방편이 P2-f 에서 제거돼 둘만
   * 남았다. 남은 둘은 실패 정책이 정반대(fail-closed 403 / 정상 UX 분기)라, 공유할 수 있는 것은 이미
   * 이 메서드가 공유하고 있는 <b>규칙</b>뿐이고 정책을 enum 인자 따위로 끌어모으면 위 문단의 설계
   * 의도를 반쯤 되돌리는 셈이 된다. 새 웹훅 경로도 멤버십이 아니라 {@code team_id} 로 해석하므로
   * 이 메서드를 쓰지 않는다.
   */
  public static Optional<Long> soleActiveTenant(List<MembershipResponse> memberships) {
    return memberships.size() == 1 ? Optional.of(memberships.get(0).tenantId()) : Optional.empty();
  }
}
