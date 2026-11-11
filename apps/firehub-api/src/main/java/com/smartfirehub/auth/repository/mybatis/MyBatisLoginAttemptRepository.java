package com.smartfirehub.auth.repository.mybatis;

import com.smartfirehub.auth.repository.LoginAttemptRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.time.LocalDateTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * LoginAttemptRepository 의 MyBatis 구현(WD-706) — {@code JooqLoginAttemptRepository} 와 같은 의미.
 *
 * <p>로그인은 테넌트가 정해지기 전이고 login_attempts 는 전역 테이블이라 테넌트 컨텍스트가 필요 없다 — 그래서 클래스 레벨 트랜잭션을 두지 않고 jOOQ 구현과
 * 같이 쓰기 메서드에만 {@code @Transactional}(REQUIRED)을 건다. 카운터 쓰기는 호출자 트랜잭션에 참여한다(WD-552, jOOQ 구현 주석 참고):
 * 요청당 커넥션 1개 원칙을 지키는 대신, 실패 카운터가 남으려면 호출자가 실패 예외에서 롤백하지 않아야 한다(AuthService·PlatformAuthService 의
 * login 이 noRollbackFor 로 지킨다). 바깥 트랜잭션이 없으면 여기서 자기 트랜잭션을 연다.
 *
 * <p>빈 문자열 계정은 {@link EmptyKeys} 센티널로 바꿔 저장·조회한다(Tibero 가 {@code ''} 를 NULL 로 보기 때문).
 */
@PlatformMyBatisRepository
public class MyBatisLoginAttemptRepository implements LoginAttemptRepository {

  private final LoginAttemptMapper mapper;

  public MyBatisLoginAttemptRepository(LoginAttemptMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * UPSERT 한 뒤 같은 트랜잭션에서 값을 읽는다. UPSERT 가 행을 잠가 두므로 읽는 값은 이 호출이 올린 값이다(동시 증가는 그 뒤에 줄 선다).
   *
   * <p>Tibero 의 MERGE 는 같은 계정이 처음으로 동시에 실패하면 한쪽이 PK 위반으로 실패할 수 있다(PG ON CONFLICT 는 그렇지 않다). 문장 단위로
   * 실패하므로 트랜잭션은 살아 있어 한 번만 다시 시도하면 이미 들어간 행을 갱신하게 된다.
   */
  @Override
  @Transactional
  public int incrementAttempts(String username) {
    String key = EmptyKeys.key(username);
    try {
      mapper.upsertAttempt(key);
    } catch (DuplicateKeyException e) {
      mapper.upsertAttempt(key);
    }
    Integer attempts = mapper.selectAttempts(key);
    // UPSERT 직후라 행이 반드시 있다 — 없으면 동시 삭제 같은 이상 상황이라 조용히 넘기지 않는다.
    if (attempts == null) {
      throw new IllegalStateException("로그인 시도 카운터를 증가시킨 직후 행을 찾지 못했습니다");
    }
    return attempts;
  }

  @Override
  public int getAttempts(String username) {
    Integer attempts = mapper.selectActiveAttempts(EmptyKeys.key(username), LocalDateTime.now());
    return attempts == null ? 0 : attempts;
  }

  @Override
  @Transactional
  public void clear(String username) {
    mapper.deleteByUsername(EmptyKeys.key(username));
  }

  @Override
  @Transactional
  public int deleteExpired() {
    return mapper.deleteExpired(LocalDateTime.now());
  }
}
