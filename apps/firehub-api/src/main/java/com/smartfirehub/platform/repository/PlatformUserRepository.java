package com.smartfirehub.platform.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.platform.dto.PlatformUserResponse;
import java.util.List;
import org.jooq.DSLContext;
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
   * @param q 검색어. 하한 검사는 서비스가 이미 마쳤다고 가정한다.
   * @param limit 결과 상한. 호출자가 항상 유한한 값을 준다.
   */
  public List<PlatformUserResponse> search(String q, int limit) {
    // LIKE 메타문자(%, _, \)를 이스케이프하지 않으면 "%" 한 글자가 전 사용자를 긁는다.
    String pattern = LikePatternUtils.containsPattern(q);

    return dsl
        .select(
            field(name("u", "id"), Long.class),
            field(name("u", "email"), String.class),
            field(name("u", "name"), String.class))
        .from(table(name("user")).as("u"))
        .where(
            field(name("u", "email"), String.class)
                .likeIgnoreCase(pattern, '\\')
                .or(field(name("u", "name"), String.class).likeIgnoreCase(pattern, '\\')))
        // 정렬을 고정해야 상한 절단이 결정적이다. id 오름차순 = 생성 순.
        .orderBy(field(name("u", "id"), Long.class).asc())
        .limit(limit)
        .fetch(
            r ->
                new PlatformUserResponse(
                    r.get(field(name("u", "id"), Long.class)),
                    r.get(field(name("u", "email"), String.class)),
                    r.get(field(name("u", "name"), String.class))));
  }
}
