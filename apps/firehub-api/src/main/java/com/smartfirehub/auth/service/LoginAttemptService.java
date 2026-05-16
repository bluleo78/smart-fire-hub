package com.smartfirehub.auth.service;

import com.smartfirehub.auth.repository.LoginAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 로그인 브루트포스 잠금 정책(#144).
 *
 * <p>5회 연속 실패 시 잠금. 카운터는 {@link LoginAttemptRepository}를 통해 PostgreSQL에 영속 저장되어 재시작·멀티 인스턴스 환경에서 일관
 * 유지된다. 잠금 기간(15분)은 Repository SQL에 인라인되어 있다.
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

  private static final int MAX_ATTEMPTS = 5;

  private final LoginAttemptRepository repository;

  /** 로그인 실패 시 카운터 1 증가. */
  public void loginFailed(String username) {
    repository.incrementAttempts(username);
  }

  /** 로그인 성공 시 카운터 제거. */
  public void loginSucceeded(String username) {
    repository.clear(username);
  }

  /** MAX_ATTEMPTS 이상 실패했고 만료 전이면 true. DB 장애 시 예외 전파(fail-closed). */
  public boolean isBlocked(String username) {
    return repository.getAttempts(username) >= MAX_ATTEMPTS;
  }
}
