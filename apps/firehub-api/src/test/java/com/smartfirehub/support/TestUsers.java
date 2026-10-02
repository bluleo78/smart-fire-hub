package com.smartfirehub.support;

import static com.smartfirehub.jooq.Tables.USER;

import com.smartfirehub.user.dto.UserResponse;
import org.jooq.DSLContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 테스트용 사용자 직접 삽입 픽스처.
 *
 * <p>왜 필요한가: WD-2 이후 공개 가입({@code AuthService.signup})은 "사용자 0명" 일 때만 열린다.
 * 공유 Testcontainers DB 에는 항상 사용자가 있으므로 가입으로 사용자를 만들던 테스트가 모두
 * SIGNUP_DISABLED 로 깨진다. 가입이 하던 일(사용자 + 테넌트 멤버십 + USER 역할)을 직접 삽입으로
 * 재현한다.
 *
 * <p>RLS: {@code "user"}·{@code membership} 은 전역 테이블이라 컨텍스트 없이 쓴다. {@code user_role}
 * 은 RLS 대상이라 {@link TenantRlsTestSupport#runInTenantTransaction} 안에서 쓴다. 클래스가
 * {@code @Transactional} 인 테스트에서는 바깥 트랜잭션에 합류하므로(GUC 는 바깥 트랜잭션 시작 시
 * 기본 테넌트로 이미 설정됨) <b>기본 테넌트(1)만</b> 써야 한다.
 */
public final class TestUsers {

  private TestUsers() {}

  /** 사용자 + 지정 테넌트의 ACTIVE(MEMBER) 멤버십 + 그 테넌트의 USER 역할을 만든다. */
  public static UserResponse createMember(
      DSLContext dsl,
      TransactionTemplate tx,
      PasswordEncoder encoder,
      String username,
      String email,
      String rawPassword,
      String name,
      long tenantId) {
    var rec =
        dsl.insertInto(USER)
            .set(USER.USERNAME, username)
            .set(USER.EMAIL, email)
            .set(USER.PASSWORD, encoder.encode(rawPassword))
            .set(USER.NAME, name)
            .returning(
                USER.ID, USER.USERNAME, USER.EMAIL, USER.NAME, USER.IS_ACTIVE, USER.CREATED_AT)
            .fetchOne();
    long userId = rec.getId();
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, tenantId);
    grantRole(dsl, tx, userId, tenantId, "USER");
    return new UserResponse(
        rec.getId(),
        rec.getUsername(),
        rec.getEmail(),
        rec.getName(),
        rec.getIsActive(),
        rec.getCreatedAt());
  }

  /**
   * 지정 테넌트의 역할(이름)을 사용자에게 붙인다. 역할 조회·삽입 모두 그 테넌트 RLS 안에서 한다.
   *
   * <p>그 테넌트에 역할이 없으면 {@code insert ... select} 가 0행을 넣고 {@code on conflict do
   * nothing} 에 가려 조용히 통과한다 — 권한 없는 사용자로 테스트가 엉뚱하게 통과/실패하므로, 삽입 전에
   * 역할 존재부터 확인해 없으면 즉시 예외로 알린다(이미 붙어 있던 경우의 0행 삽입과 구분하기 위해).
   */
  public static void grantRole(
      DSLContext dsl, TransactionTemplate tx, long userId, long tenantId, String roleName) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () -> {
          boolean roleExists =
              dsl.fetchExists(dsl.selectOne().from("role").where("name = ?", roleName));
          if (!roleExists) {
            throw new IllegalStateException(
                "테넌트 " + tenantId + " 에 역할 '" + roleName + "' 이 없다 — provisionDefaults 선행 필요");
          }
          dsl.execute(
              "insert into user_role (user_id, role_id) select ?, id from role where name = ?"
                  + " on conflict do nothing",
              userId,
              roleName);
        });
  }

  /**
   * 비트랜잭션 테스트의 정리. RLS 대상(audit_log·user_role)은 테넌트마다 컨텍스트 안에서, NULL 테넌트
   * 감사 로그는 컨텍스트 없이, 전역 행(refresh_token·membership·user)은 마지막에 지운다 — 순서가
   * 바뀌면 FK 로 실패한다(옛 SignupTenantScopeTest 의 정리 순서를 옮겨 온 것).
   */
  public static void cleanup(
      DSLContext dsl, TransactionTemplate tx, long userId, long... tenantIds) {
    for (long tenantId : tenantIds) {
      TenantRlsTestSupport.runInTenantTransaction(
          tx,
          tenantId,
          () -> {
            dsl.execute("delete from audit_log where user_id = ?", userId);
            dsl.execute("delete from user_role where user_id = ?", userId);
          });
    }
    TenantRlsTestSupport.runInTenantTransaction(
        tx, null, () -> dsl.execute("delete from audit_log where user_id = ?", userId));
    dsl.execute("delete from refresh_token where user_id = ?", userId);
    TenantRlsTestSupport.deleteMembership(dsl, userId);
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }
}
