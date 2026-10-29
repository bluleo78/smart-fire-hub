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

  static final int MAX_ATTEMPTS = 5;

  private final LoginAttemptRepository repository;

  /**
   * 로그인 실패 시 카운터 1 증가. 증가 뒤 카운터가 {@link #MAX_ATTEMPTS} 이상이면 true — 호출자는 이 실패 직전에 {@link #isBlocked} 가
   * 거짓임을 확인했으므로, 이 실패로 계정이 막 잠겼다는 뜻이다(잠금 감사, WD-290). 정확히 같음으로 보지 않는 이유: 만료된 행은 카운터가 0 으로 돌아가지
   * 않고 이어 오르므로(INCREMENT_SQL), 잠금이 풀린 뒤의 첫 실패는 6 이상에서 다시 잠근다. 잠금 직전(카운터 4)에 실패 요청이 동시에 여럿 들어오면
   * 모두 true 를 받아 잠금 감사가 그 수만큼 남을 수 있다 — 빠뜨리는 것보다 낫다고 본다.
   */
  public boolean loginFailed(String username) {
    return repository.incrementAttempts(username) >= MAX_ATTEMPTS;
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
