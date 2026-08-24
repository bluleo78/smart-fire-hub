package com.smartfirehub.platform.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.when;

import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.platform.dto.PlatformUserResponse;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

/**
 * 운영자 평면 사용자 조회.
 *
 * <p><b>RLS 우회 장치가 없는 이유</b>: {@code "user"} 는 설계상 <b>전역</b> 테이블이다 —
 * {@code tenant_id} 컬럼도 RLS 정책도 없다(V1 이후 변경 없음). 한 사람이 여러 테넌트에 속할 수
 * 있어야 하고, 로그인은 테넌트가 정해지기 <b>전에</b> 사용자를 찾아야 하기 때문이다. 그래서
 * 테넌트 컨텍스트가 빈 운영자 토큰으로도 전 테넌트 사용자가 그대로 보인다.
 *
 * <p><b>절대 하지 말 것</b>: {@code role}/{@code user_role} 조인. 두 테이블은 RLS 라 GUC 가 없는
 * 운영자 토큰에서 <b>예외 없이 0행</b>을 돌려준다 — 화면에는 "검색 결과 없음"으로 보이는
 * 조용한 무동작이 된다. 이 리포지토리는 {@code "user"} 하나만 만진다.
 *
 * <p>{@code "user"} 는 PostgreSQL 예약어라 인용이 필요하다. {@code DSL.name("user")} 가 그 인용을
 * 만들어 준다. 생성 jOOQ 타입({@code Tables.USER})을 쓰지 않는 것은 이 워크트리에
 * {@code src/main/generated} 가 없어서이고, 같은 패키지의 다른 두 리포지토리도 문자열 API 를 쓴다.
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
   * <p><b>정렬은 id 오름차순(=가입 순)이 아니다.</b> 상한(20)이 있는 한 정렬이 "잘렸을 때 무엇이
   * 남는가"를 결정한다 — 가입 순으로 자르면 넓은 검색어에서 매번 가장 오래된 20명만 보이고,
   * 운영자가 찾는 사람은 영원히 화면 밖이다. 대신 이메일 정확일치를 최우선으로 올리고, 그 다음
   * 이메일·이름 오름차순으로 둔다. 동순위가 남을 수 있어 id 를 마지막 tie-breaker 로 둬 정렬을
   * 결정적으로 만든다(그래야 상한 절단이 매 호출 같은 20건을 자른다).
   *
   * <p>비활성({@code is_active = false}) 사용자는 제외한다 — 로그인할 수 없는 계정을 테넌트
   * Owner 로 지정하면 아무도 못 들어가는 테넌트가 생긴다. 응답 필드를 넓히지 않으므로(그냥
   * WHERE 조건일 뿐 응답에 활성 여부를 싣지 않는다) 열거 표면은 커지지 않는다.
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

    return dsl
        .select(idField, emailField, nameField)
        .from(table(name("user")).as("u"))
        .where(
            emailField
                .likeIgnoreCase(pattern, '\\')
                .or(nameField.likeIgnoreCase(pattern, '\\')))
        .and(isActiveField.isTrue())
        .orderBy(exactEmailMatchRank.asc(), emailField.asc(), nameField.asc(), idField.asc())
        .limit(limit)
        .fetch(r -> new PlatformUserResponse(r.get(idField), r.get(emailField), r.get(nameField)));
  }
}
