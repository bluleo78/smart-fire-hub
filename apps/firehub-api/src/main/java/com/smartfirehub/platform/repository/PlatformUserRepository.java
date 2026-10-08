package com.smartfirehub.platform.repository;

import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectCount;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.when;

import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.platform.dto.PlatformAccountResponse;
import com.smartfirehub.platform.dto.PlatformUserResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.springframework.stereotype.Repository;

/**
 * 운영자 평면 사용자 조회.
 *
 * <p><b>RLS 우회 장치가 없는 이유</b>: {@code "user"} 는 설계상 <b>전역</b> 테이블이다 — {@code tenant_id} 컬럼도 RLS 정책도
 * 없다(V1 이후 변경 없음). 한 사람이 여러 테넌트에 속할 수 있어야 하고, 로그인은 테넌트가 정해지기 <b>전에</b> 사용자를 찾아야 하기 때문이다. 그래서 테넌트
 * 컨텍스트가 빈 운영자 토큰으로도 전 테넌트 사용자가 그대로 보인다.
 *
 * <p><b>절대 하지 말 것</b>: {@code role}/{@code user_role} 조인. 두 테이블은 RLS 라 GUC 가 없는 운영자 토큰에서 <b>예외 없이
 * 0행</b>을 돌려준다 — 화면에는 "검색 결과 없음"으로 보이는 조용한 무동작이 된다. 이 리포지토리는 {@code "user"} 하나만 만진다.
 *
 * <p>{@code "user"} 는 PostgreSQL 예약어라 인용이 필요하다. {@code DSL.name("user")} 가 그 인용을 만들어 준다. 생성 jOOQ
 * 타입({@code Tables.USER})을 쓰지 않는 것은 이 워크트리에 {@code src/main/generated} 가 없어서이고, 같은 패키지의 다른 두 리포지토리도
 * 문자열 API 를 쓴다.
 */
@Repository
public class PlatformUserRepository {

  private final DSLContext dsl;

  public PlatformUserRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

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
  public List<PlatformUserResponse> search(String q, int limit) {
    // LIKE 메타문자(%, _, \)를 이스케이프하지 않으면 "%" 한 글자가 전 사용자를 긁는다.
    String pattern = LikePatternUtils.containsPattern(q);

    Field<Long> idField = field(name("u", "id"), Long.class);
    Field<String> emailField = field(name("u", "email"), String.class);
    Field<String> nameField = field(name("u", "name"), String.class);
    Field<Boolean> isActiveField = field(name("u", "is_active"), Boolean.class);

    // 이메일이 검색어와 정확히(대소문자 무시) 같으면 0, 아니면 1 — ORDER BY 에서 0 이 먼저 온다.
    Field<Integer> exactEmailMatchRank = when(emailField.equalIgnoreCase(q), 0).otherwise(1);

    return dsl.select(idField, emailField, nameField)
        .from(table(name("user")).as("u"))
        .where(emailField.likeIgnoreCase(pattern, '\\').or(nameField.likeIgnoreCase(pattern, '\\')))
        .and(isActiveField.isTrue())
        .orderBy(exactEmailMatchRank.asc(), emailField.asc(), nameField.asc(), idField.asc())
        .limit(limit)
        .fetch(r -> new PlatformUserResponse(r.get(idField), r.get(emailField), r.get(nameField)));
  }

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
  public PageResponse<PlatformAccountResponse> findAccounts(String q, int page, int size) {
    Field<Long> idField = field(name("u", "id"), Long.class);
    Field<String> usernameField = field(name("u", "username"), String.class);
    Field<String> emailField = field(name("u", "email"), String.class);
    Field<String> nameField = field(name("u", "name"), String.class);
    Field<Boolean> isActiveField = field(name("u", "is_active"), Boolean.class);
    Field<LocalDateTime> createdAtField = field(name("u", "created_at"), LocalDateTime.class);
    Field<Boolean> operatorField =
        field(
                exists(
                    selectOne()
                        .from(table(name("platform_user_role")))
                        .where(
                            field(name("platform_user_role", "user_id"), Long.class).eq(idField))))
            .as("operator");
    Field<Integer> membershipCountField =
        field(
                selectCount()
                    .from(table(name("membership")))
                    .where(field(name("membership", "user_id"), Long.class).eq(idField)))
            .as("membership_count");

    Condition condition = noCondition();
    List<SortField<?>> order;
    if (q != null) {
      // LIKE 메타문자(%, _, \)를 이스케이프하지 않으면 "%" 한 글자가 전 사용자를 긁는다.
      String pattern = LikePatternUtils.containsPattern(q);
      condition =
          emailField
              .likeIgnoreCase(pattern, '\\')
              .or(nameField.likeIgnoreCase(pattern, '\\'))
              .or(usernameField.likeIgnoreCase(pattern, '\\'));
      Field<Integer> exactEmailMatchRank = when(emailField.equalIgnoreCase(q), 0).otherwise(1);
      order = List.of(exactEmailMatchRank.asc(), emailField.asc(), nameField.asc(), idField.asc());
    } else {
      order = List.of(createdAtField.desc(), idField.desc());
    }

    long total = dsl.fetchCount(dsl.selectOne().from(table(name("user")).as("u")).where(condition));
    List<PlatformAccountResponse> content =
        dsl.select(
                idField,
                usernameField,
                emailField,
                nameField,
                isActiveField,
                operatorField,
                membershipCountField,
                createdAtField)
            .from(table(name("user")).as("u"))
            .where(condition)
            .orderBy(order)
            .limit(size)
            .offset((long) page * size)
            .fetch(
                r ->
                    new PlatformAccountResponse(
                        r.get(idField),
                        r.get(usernameField),
                        r.get(emailField),
                        r.get(nameField),
                        Boolean.TRUE.equals(r.get(isActiveField)),
                        Boolean.TRUE.equals(r.get(operatorField)),
                        r.get(membershipCountField),
                        r.get(createdAtField)));
    int totalPages = (int) ((total + size - 1) / size);
    return new PageResponse<>(content, page, size, total, totalPages);
  }
}
