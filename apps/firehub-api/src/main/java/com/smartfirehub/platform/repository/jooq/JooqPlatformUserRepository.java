package com.smartfirehub.platform.repository.jooq;

import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectCount;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.when;

import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.platform.dto.PlatformAccountResponse;
import com.smartfirehub.platform.dto.PlatformUserResponse;
import com.smartfirehub.platform.repository.PlatformUserRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.springframework.stereotype.Repository;

/**
 * PlatformUserRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>운영자 평면 사용자 조회.
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
@ConditionalOnPostgresPlatformDb
public class JooqPlatformUserRepository implements PlatformUserRepository {

  private final DSLContext dsl;

  public JooqPlatformUserRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
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

  @Override
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
