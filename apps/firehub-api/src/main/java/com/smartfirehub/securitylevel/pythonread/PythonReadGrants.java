package com.smartfirehub.securitylevel.pythonread;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantBackend.NarrowResult;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PYTHON 읽기 슬롯 GRANT 동기화의 <b>백엔드 분기 파사드</b>(WD-78). 호출부(러너·등급 이벤트 리스너·야간 스케줄러)는 이 클래스만 부르고, 현재 테넌트의
 * 저장소 백엔드에 따라 PG({@link PythonReadGrantSync} 그대로)나 Tibero({@link TiberoPythonReadGrantSync})로 보낸다.
 *
 * <p><b>PG 동작 불변.</b> PG 테넌트(와 테넌트 컨텍스트가 없는 호출)는 예전과 똑같이 {@link PythonReadGrantSync} 의 같은 메서드를 한 번
 * 부른다 — 컨텍스트가 없을 때의 예외·로그도 PG 구현이 그대로 낸다. 백엔드는 {@link DataStoreResolver#backendFor} 로 정한다(테넌트별 캐시).
 */
@Slf4j
@Service
public class PythonReadGrants {

  private final DataStoreResolver resolver;
  private final PythonReadGrantSync pg;
  private final TiberoPythonReadGrantSync tibero;

  /**
   * 커밋 직전 작업(좁히기·토큰 무효화)을 감싸는 savepoint 템플릿(WD-624). 그 안의 예외는 savepoint 까지만 되돌려 바깥(게시자) 트랜잭션을 살린다 —
   * PG 는 문장 하나가 실패하면 트랜잭션 전체가 aborted 가 되므로 savepoint 없이는 뒤따르는 대체 경로 문장도, 게시자의 커밋도 실패한다. null 이면(단위
   * 테스트용 생성자) 감싸지 않고 바로 부른다.
   */
  private final TransactionTemplate savepoint;

  @Autowired
  public PythonReadGrants(
      DataStoreResolver resolver,
      PythonReadGrantSync pg,
      TiberoPythonReadGrantSync tibero,
      PlatformTransactionManager txManager) {
    this.resolver = resolver;
    this.pg = pg;
    this.tibero = tibero;
    TransactionTemplate nested = new TransactionTemplate(txManager);
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    this.savepoint = nested;
  }

  /** 단위 테스트용 — savepoint 없이 백엔드를 바로 부른다. */
  PythonReadGrants(
      DataStoreResolver resolver, PythonReadGrantSync pg, TiberoPythonReadGrantSync tibero) {
    this.resolver = resolver;
    this.pg = pg;
    this.tibero = tibero;
    this.savepoint = null;
  }

  /**
   * 커밋 직전 작업을 savepoint 안에서 돌린다 — 예외는 savepoint 를 되돌린 뒤 그대로 던진다(부르는 쪽이 삼켜 대체 경로로 간다). {@code
   * rollbackIf} 가 결과를 보고 true 면 예외 없이도 savepoint 를 되돌린다: 백엔드가 문장 오류를 잡아 "확인 못 함" 으로 돌려준 경우, 그 오류로
   * PG 서브트랜잭션이 aborted 로 남았을 수 있어 RELEASE 대신 ROLLBACK TO SAVEPOINT 로 끝내야 게시자 커밋이 살아남는다(대체 경로로 가므로
   * 되돌려도 잃는 것이 없다).
   */
  private <T> T inSavepoint(Supplier<T> work, Predicate<T> rollbackIf) {
    if (savepoint == null) {
      return work.get();
    }
    return savepoint.execute(
        status -> {
          T result = work.get();
          if (rollbackIf.test(result)) {
            status.setRollbackOnly();
          }
          return result;
        });
  }

  /**
   * 현재 테넌트의 백엔드 구현. 컨텍스트가 없으면 PG — PG 구현이 예전 그대로 처리(예외·로그)하게 한다. 백엔드 조회 실패는 그대로 던진다(호출부마다
   * fail-closed·로그만 중 하나로 처리한다).
   */
  private PythonReadGrantBackend delegate() {
    Long tenantId = TenantContext.get();
    return tenantId != null && resolver.backendFor(tenantId) == DataStoreBackend.TIBERO
        ? tibero
        : pg;
  }

  /** PYTHON 실행 직전 준비 — 실행 슬롯 번호(1~10). 실패는 {@link PythonReadAccessException}. */
  public int prepareForRun(Clearance runAs, int readRank) {
    PythonReadGrantBackend backend;
    try {
      backend = delegate();
    } catch (RuntimeException e) {
      // 백엔드를 알 수 없으면 PG 로 보내지 않는다 — Tibero 테넌트가 PG 슬롯 경로로 잘못 가면 안 된다(fail-closed). 조회 실패는 캐시되지 않으므로
      // 다음 실행이 다시 시도한다.
      throw new PythonReadAccessException("Python 읽기 권한을 준비하지 못해 실행을 중단했습니다.", e);
    }
    return backend.prepareForRun(runAs, readRank);
  }

  /** 현재 테넌트 전체 재동기화. */
  public void syncTenant() {
    delegate().syncTenant();
  }

  /** 등급 정의 변경 뒤 전체 재동기화 — PG 는 새 GRANT 전에 슬롯 롤 세션을 끊고, Tibero 는 끊지 않는다(스펙 §5.1). */
  public void syncTenantForLevelsChange() {
    delegate().syncTenantForLevelsChange();
  }

  /**
   * 실행 중 스크립트의 옛 읽기 자격을 <b>커밋 직전, 현재 트랜잭션에서</b> 무효로 만든다(WD-624) — Tibero 스크립트 토큰 세대 번호를 올린다({@link
   * TiberoPythonReadGrantSync#bumpScriptTokenEpochInCurrentTx}). 백엔드를 묻지 않는다: PG 테넌트는 저장소 행이 없어
   * 아무것도 바뀌지 않는다.
   *
   * <p><b>게시자를 절대 롤백시키지 않는다.</b> savepoint 안에서 돌리고, 실패하면 savepoint 만 되돌린 뒤 ERROR 로그와 false 를 낸다 —
   * 부르는 쪽이 대체 경로(커밋 뒤 실행기의 번호 올리기)로 막는다.
   *
   * @return 이 트랜잭션에서 번호를 올렸으면(또는 올릴 행이 없는 PG 테넌트면) true, 실패하면 false
   */
  public boolean invalidateScriptTokensInCurrentTx(long tenantId) {
    try {
      inSavepoint(() -> tibero.bumpScriptTokenEpochInCurrentTx(tenantId), r -> false);
      return true;
    } catch (RuntimeException e) {
      log.error("커밋 직전 스크립트 토큰 무효화 실패 — 커밋 뒤 대체 경로로 막는다: tenant={}", tenantId, e);
      return false;
    }
  }

  /**
   * 커밋 직후 주어진 PG 슬롯 롤 세션에 미확인 표식을 달고 끊기를 요청한다 — 기다리지 않는다(WD-624 → WD-664 9, {@link
   * PythonReadGrantSync#terminateSlotSessionsAfterCommit}). 백엔드를 묻지 않는다 — 커밋 콜백에서 백엔드 조회(캐시 미스)가 바깥
   * 커넥션을 다시 쓰지 않게. Tibero 테넌트는 PG 슬롯 롤 세션이 없어 끊을 것이 없다(조회 한 번).
   */
  public void terminateSlotSessionsAfterCommit(long tenantId, Set<Integer> slots) {
    pg.terminateSlotSessionsAfterCommit(tenantId, slots);
  }

  /**
   * 확인된 커밋 직전 회수 뒤 실제로 회수한 PG 슬롯의 세션 끊기를 요청만 한다 — 기다리지 않고 표식도 달지 않는다(WD-664 7, {@link
   * PythonReadGrantSync#requestSlotSessionTermination}). 커밋 콜백이 부르므로 백엔드를 묻지 않는다(위와 같은 이유).
   */
  public CompletableFuture<Integer> requestSlotSessionTermination(
      long tenantId, Set<Integer> slots) {
    return pg.requestSlotSessionTermination(tenantId, slots);
  }

  /**
   * PG 슬롯 세션을 끊고 기다린다 — 실행기 재시도 작업 전용(WD-664 7). 못 끊으면 일시 실패({@link
   * PythonReadGrantBackend.SessionKillFailed})를 던져 실행기가 다시 한다({@link
   * PythonReadGrantSync#terminateSlotSessionsOrThrow}).
   */
  public void terminateSlotSessionsOrThrow(long tenantId, Set<Integer> slots) {
    pg.terminateSlotSessionsOrThrow(tenantId, slots);
  }

  /**
   * 데이터셋 등급 변경 트랜잭션의 커밋 직전, 그 트랜잭션에서 남는 슬롯 권한만 회수한다(WD-624, {@link
   * PythonReadGrantBackend#narrowDatasetInCurrentTx}). 커밋 전이라 백엔드 조회는 이 트랜잭션에 정상 합류한다.
   *
   * <p><b>게시자를 절대 롤백시키지 않는다.</b> savepoint 안에서 돌리고, 어떤 예외든 savepoint 만 되돌린 뒤 "확인 못 함 + 모든 슬롯" 으로 바꿔
   * 돌려준다 — 등급 이전값을 알 수 없을 수도 있으니 가장 넓게 막는다(fail-closed).
   */
  public NarrowResult narrowDatasetInCurrentTx(long datasetId, Long fromLevelId, long toLevelId) {
    try {
      return inSavepoint(
          () -> delegate().narrowDatasetInCurrentTx(datasetId, fromLevelId, toLevelId),
          r -> !r.confirmed());
    } catch (RuntimeException e) {
      log.error("커밋 직전 데이터셋 등급 좁히기 실패 — 대체 경로로 막는다: dataset={}", datasetId, e);
      return NarrowResult.unconfirmed(PythonReadSlots.ALL_SLOTS);
    }
  }

  /**
   * 커밋 직전 좁히기를 확인하지 못한 데이터셋 등급 변경의 뒷정리(실행기, WD-624, {@link
   * PythonReadGrantBackend#syncDatasetAfterUnconfirmedNarrowing}). 실패는 예외 — 실행기 작업이 로그로 남긴다.
   */
  public void syncDatasetAfterUnconfirmedNarrowing(
      long datasetId, Set<Integer> exposedSlots, boolean tokensInvalidated) {
    delegate().syncDatasetAfterUnconfirmedNarrowing(datasetId, exposedSlots, tokensInvalidated);
  }

  /** 데이터셋 하나의 테이블만 재동기화. */
  public void syncDataset(long datasetId) {
    delegate().syncDataset(datasetId);
  }

  /**
   * 데이터셋 여러 개(id 문자열)의 테이블을 잠금 한 번에 재동기화한다 — 데이터셋 등급 변경 리스너의 커밋 뒤 실행기 시도(WD-624 → WD-644, {@link
   * PythonReadGrantBackend#syncDatasetsWith}). 실행기 스레드에서 부르므로 여기서 백엔드를 물어도 바깥 커넥션을 다시 쓰지 않는다.
   *
   * @return 잠금이 바빠 아무것도 하지 않았으면 BUSY(실행기가 다시 한다), 아니면 실패한 데이터셋 id(없으면 빈 맵 — 실행기가 그 id 만 다시 하거나 버린다)
   */
  public PythonReadGrantBackend.BatchResult syncDatasetsWith(
      List<String> datasetIds, Duration lockWait) {
    return delegate().syncDatasetsWith(datasetIds, lockWait);
  }

  /**
   * 쓰기 경로 훅 — 그 테이블만 재동기화한다. 트랜잭션 안이면 커밋 뒤 {@link PythonReadSyncExecutor} 에서 비동기로(요청이 돌아온 시점엔 GRANT
   * 가 아직 반영되지 않았을 수 있다), 트랜잭션 동기화가 없으면 즉시 돌며 실패는 로그만 남긴다.
   */
  public void syncTableAfterCommit(String tableName) {
    PythonReadGrantBackend backend;
    try {
      backend = delegate();
    } catch (RuntimeException e) {
      // 이 훅은 이중 방어선이라 커밋을 막지 않는다(PG 구현과 같은 계약) — 백엔드를 몰라 건너뛰면 다음 실행 직전·야간 동기화가 회복한다.
      log.warn("PYTHON 읽기 권한 동기화 예약 실패(백엔드 조회 실패, 다음 동기화에서 회복): table={}", tableName, e);
      return;
    }
    backend.syncTableAfterCommit(tableName);
  }

  /** 실행 중 등급 정의 변경으로 슬롯 세션이 끊겼는가 — 러너의 실패 문구용. Tibero 는 세션을 끊지 않으므로(문장 단위 권한 확인) 항상 false. */
  public boolean slotSessionsTerminatedSince(long tenantId, long sinceMillis) {
    DataStoreBackend backend;
    try {
      backend = resolver.backendFor(tenantId);
    } catch (RuntimeException e) {
      // 실패 문구를 꾸미는 안내용 판정이다 — 백엔드 조회 실패가 원래 스크립트 오류를 가리면 안 되므로 경고만 남기고 "끊기지 않음"으로 본다(코드리뷰 2).
      // PG 구현으로 보내지 않는다(백엔드를 모르면 PG 경로로 새지 않는다 — prepareForRun 과 같은 원칙).
      log.warn("슬롯 세션 끊김 판정 생략(백엔드 조회 실패): tenantId={}", tenantId, e);
      return false;
    }
    if (backend == DataStoreBackend.TIBERO) {
      return false;
    }
    return pg.slotSessionsTerminatedSince(tenantId, sinceMillis);
  }
}
