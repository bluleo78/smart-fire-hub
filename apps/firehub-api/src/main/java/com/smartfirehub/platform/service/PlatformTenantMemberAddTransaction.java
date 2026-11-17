package com.smartfirehub.platform.service;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.platform.dto.PlatformAddMemberRequest;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import com.smartfirehub.user.service.TenantMemberAdder;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자 콘솔 멤버 추가의 트랜잭션 본체(WD-750).
 *
 * <p>왜 {@link PlatformTenantMemberService} 에서 분리했는가: {@code role}·{@code user_role} 은 RLS 테이블이고 테넌트
 * GUC 는 {@code TenantAwareTransactionManager.doBegin} 에서만 주입된다. 그래서 호출자가 {@code TenantContext} 범위를
 * 먼저 잡고 그 안에서 이 빈의 {@code @Transactional} 메서드를 불러야 대상 테넌트로 트랜잭션이 열린다. 같은 클래스 안 자기호출은 프록시를 건너뛰어
 * {@code @Transactional} 이 적용되지 않으므로 경계를 별도 빈으로 뺐다(회원가입 {@code SignupTransaction} 과 같은 방식).
 */
@Component
@RequiredArgsConstructor
public class PlatformTenantMemberAddTransaction {

  private final RoleRepository roleRepository;
  private final TenantMemberAdder tenantMemberAdder;
  private final UserRepository userRepository;

  /**
   * 트랜잭션 결과.
   *
   * @param user 멤버가 된 계정
   * @param created 이번에 계정을 새로 만들었는지
   * @param roleNames 이번에 부여한 역할 이름(감사 details 용)
   */
  public record Added(UserResponse user, boolean created, List<String> roleNames) {}

  /**
   * 대상 테넌트의 기본 역할을 찾아 멤버를 붙인다. 호출자가 {@code TenantContext.runScopedGet(tenantId, ...)} 범위 안에서 불러야 한다
   * — 그래야 역할 조회·부여가 대상 테넌트 행에만 닿는다.
   *
   * @param tenantId 대상 테넌트 id(범위와 같은 값)
   * @param request 추가 요청. userId 가 있으면 기존 계정 경로, 없으면 이메일 경로(정규화는 {@link TenantMemberAdder} 가 한다)
   */
  @Transactional
  public Added execute(long tenantId, PlatformAddMemberRequest request) {
    // USER 는 모든 멤버의 기본 역할이다 — 권한이 하나도 없는 멤버를 만들지 않는다(웹 멤버 추가와 같은 규칙).
    // ADMIN 은 요청했을 때만 조회한다. 역할 id(부여용)와 이름(감사용)을 한 번의 순회로 함께 만든다.
    List<RoleResponse> roles =
        request.admin()
            ? List.of(systemRole("USER"), systemRole("ADMIN"))
            : List.of(systemRole("USER"));
    List<Long> roleIds = new ArrayList<>(roles.size());
    List<String> roleNames = new ArrayList<>(roles.size());
    for (RoleResponse role : roles) {
      roleIds.add(role.id());
      roleNames.add(role.name());
    }
    // 멤버십·역할·중복 409 번역·이메일 정규화는 웹 경로와 공유하는 TenantMemberAdder 가 맡는다. 이 트랜잭션에 합류하므로
    // 중간에 실패하면 부분 생성된 계정·멤버십도 함께 롤백된다.
    TenantMemberAdder.Result result;
    if (request.byUserId()) {
      // 기존 계정 경로: 콘솔이 고른 계정을 id 로 찾는다(username ≠ email 인 계정도 정확히 그 계정).
      UserResponse user =
          userRepository
              .findById(request.userId())
              .orElseThrow(
                  () ->
                      new CodedApiException(
                          HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "계정을 찾을 수 없습니다"));
      // 이미 멤버 → 비활성 순서(웹과 같음)로 판정한다.
      result =
          tenantMemberAdder.addExisting(
              tenantId, user, roleIds, TenantMemberAdder.CONSOLE_INACTIVE_CHECK);
    } else {
      // 이메일 경로: adder 가 이메일(= username)로 계정을 한 번만 찾는다. 기존 계정이면 붙이기 전에 활성 검사를 부르고, 없으면 새로 만든다.
      result =
          tenantMemberAdder.add(
              tenantId,
              request.email(),
              request.name(),
              request.temporaryPassword(),
              roleIds,
              TenantMemberAdder.CONSOLE_INACTIVE_CHECK);
    }
    return new Added(result.user(), result.created(), roleNames);
  }

  /** 현재(대상) 테넌트의 시스템 역할. 없으면 프로비저닝 손상이라 시스템 역할 전용 문구로 알린다. */
  private RoleResponse systemRole(String name) {
    return roleRepository
        .findByName(name)
        .orElseThrow(() -> RoleNotFoundException.systemRole(name));
  }
}
