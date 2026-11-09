package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.datastore.DataStoreDb;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 실행기를 끈 PYTHON 스텝의 REPLACE 가 출력 테이블 직렬화 잠금을 <b>맞바꿈 트랜잭션 안에서</b> 잡게 하는 장치(WD-690).
 *
 * <p>키는 SQL 스텝의 「비우기 + 적재」와 같다({@link SqlScriptExecutor#outputLockKeyFor}) — 그래서 같은 출력의 두 경로가 서로
 * 기다린다. 잠금은 맞바꿈이 도는 데이터 저장소 연결에서 잡혀야 하므로 {@code writeToOutput} 트랜잭션 안에서만 부른다(연결이 플랫폼 트랜잭션에 묶인다). 대기
 * 시간 제한은 두지 않는다 — 대기는 플랫폼 쓰기 트랜잭션(데이터셋 KEY SHARE 행 잠금 뒤) 안에서 일어나, 같은 출력의 SQL 스텝이 끝날 때까지 플랫폼 커넥션과 행
 * 잠금을 쥔다(그 출력의 데이터셋 삭제·필드 변경이 함께 기다린다). 「플랫폼 잠금 먼저, 데이터 저장소 잠금 나중」 순서(WD-493)를 지키려는 선택이며 실행기를 끈 로컬
 * 경로 전용이다.
 */
@Component
public class OutputSwapLock {

  /** 데이터 저장소 쿼리 진입점 — 맞바꿈과 같은 트랜잭션 연결을 쓴다. */
  private final DSLContext dsl;

  /**
   * Lombok {@code @RequiredArgsConstructor} 는 한정자({@code @DataStoreDb})를 생성자 인자로 옮기지 못해 명시 생성자를 쓴다.
   *
   * @param dsl 데이터 저장소 DSLContext — SQL 스텝의 출력 잠금과 같은 DB 에서 잠금을 잡기 위해
   */
  public OutputSwapLock(@DataStoreDb DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * 출력 테이블의 직렬화 잠금을 <b>현재 트랜잭션</b>에서 잡는다. 같은 출력의 잠금을 다른 트랜잭션이 쥐고 있으면 그 트랜잭션이 끝날 때까지 기다린다(거부하지 않는다).
   * 잠금은 현재 트랜잭션의 커밋·롤백 때 풀린다.
   *
   * @param outputTableName 출력 테이블명(스키마 미포함). 현재 테넌트 스키마로 한정해 키를 만든다.
   * @throws IllegalStateException 실제 트랜잭션과 트랜잭션 동기화가 모두 켜져 있지 않을 때 — 트랜잭션 범위 잠금은 자동 커밋 문장에서 즉시 풀려
   *     아무것도 막지 못하기 때문
   */
  public void lockInCurrentTransaction(String outputTableName) {
    // 데이터 저장소 연결을 플랫폼 트랜잭션에 묶는 쪽(DataStoreConnectionBinding)은 실제 트랜잭션과 동기화가 둘 다 켜졌을 때만 묶는다 —
    // 하나라도 꺼져 있으면 잠금 문장이 자동 커밋 연결에서 돌아 곧바로 풀리므로 막지 못한다.
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("출력 잠금은 트랜잭션 안에서만 잡을 수 있습니다");
    }
    dsl.fetch(
        SqlScriptExecutor.OUTPUT_LOCK_SQL, SqlScriptExecutor.outputLockKeyFor(outputTableName));
  }
}
