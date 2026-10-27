package com.smartfirehub.pipeline.service;

import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "조회 → 실행(→ 후처리)" 서비스 메서드의 트랜잭션 구간을 테넌트 저장소 백엔드에 따라 나누는 단일 지점(WD-231).
 *
 * <p>왜 필요한가: PG 테넌트는 RLS GUC 가 필요한 조회와 데이터 질의가 같은 트랜잭션 하나에서 도는 것이 맞다. 그러나 Tibero 질의는 네트워크 너머에서 최대
 * 35초까지 걸릴 수 있어, 그 동안 PG 커넥션을 쥐지 않도록 실행 구간만 트랜잭션 밖으로 빼야 한다(WD-207). 이 분기를 서비스 여섯 곳(호출처 일곱 곳)이
 * {@code isTibero()} 로 각자 손으로 썼고, 한 곳만 어긋나면 PG 에서는 보이지 않고 Tibero 에서만 커넥션이 샌다. 그래서 한 곳에 모은다.
 *
 * <p>정적 도우미이며 스프링 빈이 아니다 — 기존 서비스 테스트가 서비스를 {@code new XService(...)} 로 직접 만들므로 생성자를 바꾸지 않으려는 선택이다.
 * 각 서비스가 이미 가진 {@link PipelineDataSchema} 를 첫 인자로 받아 여기서만 {@code isTibero()} 를 본다.
 *
 * <p>람다(supplier·function)가 던지는 예외는 가공 없이 그대로 올라간다. PG 에서는 그 예외가 트랜잭션 전체를 롤백시킨다.
 */
public final class StoreTransactions {

  private StoreTransactions() {}

  /**
   * 조회 후 실행한다.
   *
   * <ul>
   *   <li>PG: {@code pgTx} 안에서 {@code execute(load())} 전체를 한 트랜잭션으로 실행한다.
   *   <li>Tibero: {@code load} 는 {@code tiberoLoadTx} 안에서(null 이면 트랜잭션 없이) 부르고, {@code execute} 는
   *       트랜잭션 밖에서 부른다.
   * </ul>
   *
   * @param tiberoLoadTx Tibero 에서 조회를 감쌀 템플릿. null 이면 조회도 트랜잭션 없이 돈다(리포지토리가 자체 트랜잭션을 쓰는 경우).
   */
  public static <L, R> R loadThenExecute(
      PipelineDataSchema schema,
      TransactionTemplate pgTx,
      TransactionTemplate tiberoLoadTx,
      Supplier<L> load,
      Function<L, R> execute) {
    if (!schema.isTibero()) {
      return pgTx.execute(status -> execute.apply(load.get()));
    }
    return execute.apply(loadTibero(tiberoLoadTx, load));
  }

  /**
   * 조회 → 실행 → 후처리. {@link #loadThenExecute} 와 같되 후처리까지 포함한다.
   *
   * <ul>
   *   <li>PG: {@code pgTx} 하나 안에서 세 단계를 모두 실행한다.
   *   <li>Tibero: 조회는 {@code tiberoLoadTx}(null 이면 트랜잭션 없이), 실행은 트랜잭션 밖, 후처리는 {@code afterTx} 로 따로
   *       감싼다. 실행이 예외면 후처리는 돌지 않는다.
   * </ul>
   */
  public static <L, R, A> A loadExecuteThen(
      PipelineDataSchema schema,
      TransactionTemplate pgTx,
      TransactionTemplate tiberoLoadTx,
      Supplier<L> load,
      Function<L, R> execute,
      TransactionTemplate afterTx,
      Function<R, A> after) {
    if (!schema.isTibero()) {
      return pgTx.execute(status -> after.apply(execute.apply(load.get())));
    }
    R executed = execute.apply(loadTibero(tiberoLoadTx, load));
    return afterTx.execute(status -> after.apply(executed));
  }

  /** Tibero 조회 구간 — 템플릿이 있으면 그 안에서, 없으면 트랜잭션 없이 부른다. */
  private static <L> L loadTibero(TransactionTemplate tiberoLoadTx, Supplier<L> load) {
    return tiberoLoadTx == null ? load.get() : tiberoLoadTx.execute(status -> load.get());
  }
}
