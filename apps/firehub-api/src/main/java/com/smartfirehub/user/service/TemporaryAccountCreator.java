package com.smartfirehub.user.service;

import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 관리자가 임시 비밀번호로 새 계정을 만드는 공통 절차(WD-2 멤버 추가 · WD-46 운영자 계정 생성).
 *
 * <p>왜 따로 뺐나: 워크스페이스 관리자의 멤버 추가와 운영자 콘솔의 계정 생성이 "username = 소문자 이메일, 비밀번호 해시, 첫 로그인 변경 강제" 라는 같은
 * 규칙으로 계정을 만들어야 한다. 두 곳에 복사하면 한쪽만 고쳐져 갈라진다.
 *
 * <p>{@code @Transactional} 을 달지 않는 이유: 호출자 트랜잭션 안에서 그대로 실행돼야 한다. 프록시 경계를 하나 더 두면 여기서 던진 런타임 예외가
 * 경계를 지나며 바깥 트랜잭션을 rollback-only 로 표시해, 호출자가 예외를 409 로 번역해도 커밋 시점에 UnexpectedRollback 이 난다.
 */
@Component
@RequiredArgsConstructor
public class TemporaryAccountCreator {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  /**
   * 임시 비밀번호(변경 강제 표식 on)로 새 계정을 만든다. 멤버십·역할은 만들지 않는다 — 소속은 호출자 몫이다.
   *
   * @param normalizedEmail 호출자가 trim + 소문자(Locale.ROOT)로 정규화한 이메일. username 으로도 쓴다
   * @param name 표시 이름(앞뒤 공백은 여기서 제거)
   * @param rawTemporaryPassword 평문 임시 비밀번호 — 해시만 저장하고 어디에도 남기지 않는다
   * @throws EmailAlreadyExistsException 다른 계정이 이 이메일을 대소문자 무시로 이미 쓰고 있을 때
   */
  public UserResponse create(String normalizedEmail, String name, String rawTemporaryPassword) {
    // username 은 없는데 다른 계정이 이 이메일을 쓰고 있으면 같은 사람의 두 번째 계정이 된다 — 거부.
    // 대소문자 무시: 과거 계정의 이메일이 대소문자 섞여 저장돼 있어도 같은 사람으로 본다(WD-2 리뷰 지적 4).
    if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
      throw new EmailAlreadyExistsException("이미 사용 중인 이메일입니다.");
    }
    return userRepository.saveWithTemporaryPassword(
        normalizedEmail,
        normalizedEmail,
        passwordEncoder.encode(rawTemporaryPassword),
        name.trim());
  }
}
