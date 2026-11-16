package com.smartfirehub.auth.service;

import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.exception.UsernameAlreadyExistsException;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.platform.repository.PlatformRoleRepository;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입의 트랜잭션 본체.
 *
 * <p>왜 AuthService 에서 분리했는가: TenantContext 는 트랜잭션이 <b>열리기 전에</b> 세팅돼야 한다(GUC 는
 * TenantAwareTransactionManager.doBegin 에서만 주입된다). AuthService 안에서 자기호출로 감싸면 Spring AOP 프록시를
 * 우회해 @Transactional 이 적용되지 않으므로, 트랜잭션 경계를 별도 빈으로 뺐다.
 */
@Service
@RequiredArgsConstructor
public class SignupTransaction {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordEncoder passwordEncoder;
  private final MembershipRepository membershipRepository;
  private final PlatformRoleRepository platformRoleRepository;

  /**
   * 공개 가입 — 시스템 첫 사용자(부트스트랩) 전용(WD-2).
   *
   * <p>순서가 중요하다. (1) advisory lock 으로 동시 첫 가입을 직렬화하고 (2) 사용자가 이미 있으면 즉시 403 SIGNUP_DISABLED. 아이디 중복
   * 검사를 그 <b>뒤</b>에 두는 이유: 앞에 두면 닫힌 가입에서도 "이미 사용 중인 아이디" 409 가 나와 계정 존재 여부가 새어 나간다. 이후 사용자는 워크스페이스
   * 관리자가 멤버 추가로 만든다.
   *
   * <p>첫 가입자는 기본 테넌트 USER·ADMIN 과 함께 플랫폼 SUPER_ADMIN 도 받는다(WD-748) — 새 설치본에서 운영자 콘솔에 들어갈 계정이 이 사람
   * 하나뿐이기 때문이다. 세 역할 중 하나라도 시드가 없으면 예외로 가입 전체(사용자·역할·멤버십)를 되돌린다.
   */
  @Transactional
  public UserResponse execute(SignupRequest request) {
    userRepository.acquireFirstUserLock();
    // 전역(테넌트 무관) 판정이어야 한다 — "시스템 최초 사용자에게 ADMIN" 이라는 부트스트랩 의미다.
    // countAll 은 현재 테넌트 멤버로 좁혀지므로 여기서 쓰면 "이 테넌트의 첫 멤버" 로 뜻이 바뀐다.
    if (userRepository.existsAnyUser()) {
      throw new CodedApiException(HttpStatus.FORBIDDEN, "SIGNUP_DISABLED", "계정은 워크스페이스 관리자가 추가합니다");
    }
    // 사용자 0명이 잠금 안에서 확정된 뒤라 아래 중복 검사는 사실상 도달하지 않는다 — 의도된 방어 코드다.
    if (userRepository.existsByUsername(request.username())) {
      // 사용자에게 한국어 메시지 반환 — 영문 원문 메시지 노출 방지
      throw new UsernameAlreadyExistsException("이미 사용 중인 아이디입니다.");
    }

    String encodedPassword = passwordEncoder.encode(request.password());
    UserResponse user =
        userRepository.save(request.username(), request.email(), encodedPassword, request.name());

    // 역할 조회는 RLS 아래에서 기본 테넌트 컨텍스트를 요구한다 — 호출자(AuthService.signup)가
    // runScopedGet 으로 감싸 주므로 여기서는 그냥 이름으로 찾는다.
    Long userRoleId =
        roleRepository
            .findByName("USER")
            .orElseThrow(() -> RoleNotFoundException.systemRole("USER"))
            .id();
    userRepository.addRole(user.id(), userRoleId);

    // 첫 사용자 = 시스템 관리자: USER + ADMIN + 플랫폼 SUPER_ADMIN (위에서 사용자 0명임이 잠금 안에서 확정됐다).
    Long adminRoleId =
        roleRepository
            .findByName("ADMIN")
            .orElseThrow(() -> RoleNotFoundException.systemRole("ADMIN"))
            .id();
    userRepository.addRole(user.id(), adminRoleId);

    // 플랫폼(운영자) 평면 SUPER_ADMIN(WD-748). 새 설치본에는 V82 승격 대상(기존 전역 ADMIN)이 없어 아무도 운영자 콘솔에
    // 들어갈 수 없었다. 역할 id 는 벤더마다 다를 수 있어 이름으로 찾는다. 시드가 없으면(false) USER·ADMIN 이 없을 때와 같이
    // 예외로 가입을 되돌린다 — 운영자 없는 설치본을 만들지 않는다. 두 번째 이후 가입은 위의 403 에서 끝나 여기 닿지 않는다.
    if (!platformRoleRepository.grantPlatformRole(user.id(), "SUPER_ADMIN")) {
      throw RoleNotFoundException.platformRole("SUPER_ADMIN");
    }

    // 멤버십이 하나도 없으면 테넌트 미선택 토큰만 발급되어 RLS 가 모든 API 를 막는다(잠김).
    // 자가 가입 사용자를 잠그지 않기 위해 가입과 동시에 기본 워크스페이스에 합류시킨다.
    membershipRepository.createDefaultMembership(user.id());

    return user;
  }
}
