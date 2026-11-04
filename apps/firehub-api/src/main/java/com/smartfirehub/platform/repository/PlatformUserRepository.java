package com.smartfirehub.platform.repository;

import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.platform.dto.PlatformAccountResponse;
import com.smartfirehub.platform.dto.PlatformUserResponse;
import java.util.List;

/**
 * 플랫폼 운영 콘솔용 사용자(user 테이블) 검색·계정 조회 리포지토리. membership·platform_user_role 을 함께 읽는다.
 *
 * <p>구현은 {@code JooqPlatformUserRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface PlatformUserRepository {

  /**
   * 이메일/이름 부분일치 검색.
   *
   * <p><b>정렬은 id 오름차순(=가입 순)이 아니다.</b> 상한(20)이 있는 한 정렬이 "잘렸을 때 무엇이 남는가"를 결정한다 — 가입 순으로 자르면 넓은 검색어에서
   * 매번 가장 오래된 20명만 보이고, 운영자가 찾는 사람은 영원히 화면 밖이다. 대신 이메일 정확일치를 최우선으로 올리고, 그 다음 이메일·이름 오름차순으로 둔다. 동순위가
   * 남을 수 있어 id 를 마지막 tie-breaker 로 둬 정렬을 결정적으로 만든다(그래야 상한 절단이 매 호출 같은 20건을 자른다).
   *
   * <p>비활성({@code is_active = false}) 사용자는 제외한다 — 로그인할 수 없는 계정을 테넌트 Owner 로 지정하면 아무도 못 들어가는 테넌트가
   * 생긴다. 응답 필드를 넓히지 않으므로(그냥 WHERE 조건일 뿐 응답에 활성 여부를 싣지 않는다) 열거 표면은 커지지 않는다.
   *
   * @param q 검색어. 하한/상한 길이 검사는 서비스가 이미 마쳤다고 가정한다.
   * @param limit 결과 상한. 호출자가 항상 유한한 값을 준다.
   */
  List<PlatformUserResponse> search(String q, int limit);

  /**
   * 계정 화면 목록(#784 검색 → WD-47 전체 목록 + 페이지네이션).
   *
   * <p>Owner 검색({@link #search})과 다른 점:
   *
   * <ul>
   *   <li>비활성 계정도 포함한다 — 비활성화한 계정을 다시 찾아 재활성화할 수 있어야 한다.
   *   <li>username(로그인 아이디)도 매칭하고 활성 여부·운영자 여부·소속 수·생성 시각을 싣는다.
   *   <li>검색어가 없으면(null) 전체 목록이다. 상한 절단 대신 페이지로 끝까지 볼 수 있다.
   * </ul>
   *
   * <p><b>정렬</b>: 검색어가 있으면 Owner 검색과 같은 "이메일 정확일치 → 이메일 → 이름 → id"(찾는 사람이 첫 줄에 오게), 없으면 생성
   * 최신순(created_at desc, id desc — 같은 시각이 남아도 결정적으로). 방금 만든 계정(WD-46)이 첫 줄에 보인다.
   *
   * <p>운영자 여부는 platform_user_role EXISTS 로 본다 — 전역 테이블이라 GUC 없는 운영자 토큰에서도 정상 동작한다 (role/user_role
   * 같은 RLS 테이블은 여기서 조인하지 않는다: 클래스 주석 참고).
   *
   * <p><b>소속 수(membershipCount)</b>: membership 행 수를 <b>상태 무관</b>(ACTIVE + SUSPENDED)으로 센다. 정지 멤버십도
   * 소속이다 — 그 워크스페이스 관리자가 재활성화하면 바로 돌아가므로, 정지만 남은 계정을 "미소속" 으로 보이면 운영자가 버려진 계정으로 오인한다. membership 은
   * 전역 테이블(V81 "RLS 미적용")이라 테넌트 목록의 멤버 수({@code PlatformTenantRepository})와 같은 상관 서브쿼리로 GUC 없이 센다.
   *
   * @param q 검색어(trim·길이 검사 완료) 또는 null(전체)
   * @param page 0부터
   * @param size 1 이상(상한 검사는 서비스)
   */
  PageResponse<PlatformAccountResponse> findAccounts(String q, int page, int size);
}
