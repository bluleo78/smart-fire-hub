package com.smartfirehub.pipeline.service;

import com.smartfirehub.pipeline.service.executor.ExecutionCancelCheck;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 출력 테이블 단위 직렬화 잠금의 <b>세션 범위</b> 판(#735) — "출력 교체 + 적재"를 한 트랜잭션으로 묶을 수 없는 경로 전용이다.
 *
 * <p><b>누가 쓰는가.</b> 실행기를 끈 PYTHON 스텝의 REPLACE 뿐이다. WD-29(R5) 이후 그 경로는 API 가 출력의 임시 테이블({@code
 * DataTableService.createTempTable})을 만들고, 스크립트 stdout JSON 을 거기에 적재한 뒤 맞바꾼다 — 세 단계가 각자 커밋되는 여러
 * 문장이라 한 트랜잭션 범위 잠금({@link SqlScriptExecutor#OUTPUT_LOCK_SQL})으로 덮을 수 없다. 임시 테이블 이름이 출력마다 고정이던 때에는
 * 잠금이 없으면 같은 출력에 겹친 실행이 서로의 임시 테이블을 지우고 덮었다. WD-151 부터 이름이 실행마다 달라 그 충돌은 이름이 막지만, 같은 출력의 REPLACE
 * 끼리·SQL 스텝과의 직렬화라는 기존 동작을 바꾸지 않으려고 이 경로의 잠금은 유지한다. 예전(WD-29 이전)에는 "truncate 후 자식 프로세스가 자기 커넥션으로
 * 적재" 구간을 덮었다. SQL 스텝은 이 클래스를 쓰지 않는다 — 트랜잭션 범위 잠금이 커밋·롤백 때 저절로 풀려 더 안전하다.
 *
 * <p><b>키는 SQL 경로와 같다.</b> {@link OutputClearStatement#deleteAll} 이 만든 선행 문장에서 {@link
 * SqlScriptExecutor#outputLockKey} 로 뽑는다(직접 조립하지 않는다 — 어긋나면 두 경로가 서로를 못 본다). PostgreSQL 의 세션
 * 범위·트랜잭션 범위 advisory 잠금은 같은 키 공간을 쓰고 세션이 다르면 서로 충돌하므로, 이 잠금을 쥔 동안에는 같은 출력에 대한 SQL 스텝의 "비우기 + 적재"도
 * 기다린다.
 *
 * <p><b>누수 방지.</b> 세션 잠금은 커밋으로 풀리지 않고 커넥션이 풀(pool)로 돌아가도 남는다 — 풀어 주지 않으면 그 커넥션이 닫힐 때까지 해당 출력의 모든
 * REPLACE 가 영원히 기다린다. 그래서
 *
 * <ul>
 *   <li>잠금을 얻은 커넥션은 본문이 도는 동안 아무에게도 내주지 않고,
 *   <li>본문이 성공하든 예외로 끝나든 {@code finally} 에서 {@code pg_advisory_unlock_all()} 로 풀며 (이 커넥션은 이 잠금만 쥐므로
 *       전부 풀어도 된다 — 획득 문장이 중간에 끊겨 잠금 여부가 모호한 경우까지 덮는다),
 *   <li>해제 문장마저 실패하면 커넥션을 <b>물리적으로 끊는다</b>({@link Connection#abort}) — 세션이 끝나면 서버가 잠금을 회수하고, 풀은 끊긴
 *       커넥션을 버린다. 잠금을 쥔 세션이 풀로 돌아가는 경로를 남기지 않는다.
 * </ul>
 *
 * <p>대기 시간 제한(lock_timeout)은 걸지 않는다 — #731 과 같은 이유다(뒤 실행을 실패로 바꾸는 것은 제품 정책 변경).
 */
@Component
@RequiredArgsConstructor
public class OutputTableSessionLock {

  /**
   * 획득 시도 문장 — 키 식은 {@link SqlScriptExecutor#OUTPUT_LOCK_SQL} 과 같아야 한다(같은 해시).
   *
   * <p><b>기다리는 {@code pg_advisory_lock} 이 아니라 시도형({@code pg_try_advisory_lock})을 폴링한다.</b> 기다리는 동안
   * 메인 풀 커넥션을 쥐고 있으면, 같은 출력에 겹친 실행이 여럿일 때 대기자들이 메인 풀을 다 차지해 잠금을 쥔 실행조차 적재·맞바꿈에 쓸 커넥션을 못 얻는 교착이
   * 생긴다(대기자는 쥔 실행을, 쥔 실행은 풀을 기다린다). 그래서 실패한 시도는 커넥션을 바로 풀에 돌려주고 잠시 뒤 다시 시도한다. 잠금을 얻은 커넥션만 본문이 끝날
   * 때까지 붙잡는다.
   */
  static final String TRY_ACQUIRE_SQL = SessionAdvisoryLock.TRY_ACQUIRE_SQL;

  /** 획득 실패 뒤 다시 시도하기까지의 간격. 로컬 전용 경로라 정밀한 공정성은 필요 없다. */
  static final long RETRY_INTERVAL_MS = SessionAdvisoryLock.RETRY_INTERVAL_MS;

  /** 해제 문장 — 이 세션이 쥔 세션 범위 advisory 잠금을 전부 푼다. */
  static final String RELEASE_SQL = SessionAdvisoryLock.RELEASE_SQL;

  /** 메인 커넥션 풀. 잠금 전용 커넥션을 여기서 하나 빌린다. */
  private final DataSource dataSource;

  /**
   * 출력 테이블의 직렬화 잠금을 쥔 채 본문을 실행한다. 다른 실행이 같은 출력의 잠금을 쥐고 있으면 그 실행이 끝날 때까지 기다린다(거부하지 않는다).
   *
   * @param outputTableName 출력 테이블명(스키마 미포함). 현재 테넌트 스키마로 한정해 키를 만든다.
   * @param body 잠금 아래에서 실행할 본문(임시 테이블 생성 + 적재 + 맞바꿈)
   * @return 본문의 반환값
   */
  public <T> T callLocked(String outputTableName, Supplier<T> body) {
    String key = SqlScriptExecutor.outputLockKey(OutputClearStatement.deleteAll(outputTableName));
    // 획득·해제·누수 방지는 공통 장치(SessionAdvisoryLock, WD-223 에서 추출)가 한다. 대기 상한·취소 확인은 걸지 않는다(위 Javadoc).
    try (SessionAdvisoryLock.Held ignored =
        SessionAdvisoryLock.acquire(
            dataSource,
            key,
            "출력 테이블 잠금",
            SessionAdvisoryLock.NO_LIMIT,
            ExecutionCancelCheck.NONE,
            () -> "출력 테이블 잠금 대기 시간이 끝났습니다")) {
      return body.get();
    }
  }
}
