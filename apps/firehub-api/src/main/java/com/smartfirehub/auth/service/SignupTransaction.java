package com.smartfirehub.auth.service;

import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.auth.exception.UsernameAlreadyExistsException;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입의 트랜잭션 본체.
 *
 * <p>왜 AuthService 에서 분리했는가: TenantContext 는 트랜잭션이 <b>열리기 전에</b> 세팅돼야
 * 한다(GUC 는 TenantAwareTransactionManager.doBegin 에서만 주입된다). AuthService 안에서
 * 자기호출로 감싸면 Spring AOP 프록시를 우회해 @Transactional 이 적용되지 않으므로,
 * 트랜잭션 경계를 별도 빈으로 뺐다.
 */
@Service
@RequiredArgsConstructor
public class SignupTransaction {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordEncoder passwordEncoder;
  private final MembershipRepository membershipRepository;

  /** 사용자 생성 + 기본 역할 부여 + 기본 테넌트 가입을 한 트랜잭션으로 처리한다. */
  @Transactional
  public UserResponse execute(SignupRequest request) {
    if (userRepository.existsByUsername(request.username())) {
      // 사용자에게 한국어 메시지 반환 — 영문 원문 메시지 노출 방지
      throw new UsernameAlreadyExistsException("이미 사용 중인 아이디입니다.");
    }
    if (request.email() != null
        && !request.email().isBlank()
        && userRepository.existsByEmail(request.email())) {
      // 사용자에게 한국어 메시지 반환 — 영문 원문 메시지 노출 방지
      throw new EmailAlreadyExistsException("이미 사용 중인 이메일입니다.");
    }

    userRepository.acquireFirstUserLock();
    // 전역(테넌트 무관) 판정이어야 한다 — "시스템 최초 사용자에게 ADMIN" 이라는 부트스트랩 의미다.
    // countAll 은 현재 테넌트 멤버로 좁혀지므로 여기서 쓰면 "이 테넌트의 첫 멤버" 로 뜻이 바뀐다.
    boolean isFirstUser = !userRepository.existsAnyUser();

    String encodedPassword = passwordEncoder.encode(request.password());
    UserResponse user =
        userRepository.save(request.username(), request.email(), encodedPassword, request.name());

    // 역할 조회는 RLS 아래에서 기본 테넌트 컨텍스트를 요구한다 — 호출자(AuthService.signup)가
    // runScopedGet 으로 감싸 주므로 여기서는 그냥 이름으로 찾는다.
    Long userRoleId =
        roleRepository
            .findByName("USER")
            .orElseThrow(() -> new RoleNotFoundException("System role not found: USER"))
            .id();
    userRepository.addRole(user.id(), userRoleId);

    if (isFirstUser) {
      Long adminRoleId =
          roleRepository
              .findByName("ADMIN")
              .orElseThrow(() -> new RoleNotFoundException("System role not found: ADMIN"))
              .id();
      userRepository.addRole(user.id(), adminRoleId);
    }

    // 멤버십이 하나도 없으면 테넌트 미선택 토큰만 발급되어 RLS 가 모든 API 를 막는다(잠김).
    // 자가 가입 사용자를 잠그지 않기 위해 가입과 동시에 기본 워크스페이스에 합류시킨다.
    membershipRepository.createDefaultMembership(user.id());

    return user;
  }
}
