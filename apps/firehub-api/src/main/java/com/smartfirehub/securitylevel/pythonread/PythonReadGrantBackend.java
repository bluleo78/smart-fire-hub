package com.smartfirehub.securitylevel.pythonread;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.global.platformdb.PlatformLockWaitTimeoutException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.pythonread.PythonReadSyncExecutor.AttemptResult;
import com.smartfirehub.securitylevel.pythonread.PythonReadSyncExecutor.Outcome;
import com.smartfirehub.securitylevel.pythonread.PythonReadSyncExecutor.PartialFailure;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PYTHON 읽기 슬롯 GRANT 동기화의 백엔드별 구현(PG {@link PythonReadGrantSync}, Tibero {@link
 * TiberoPythonReadGrantSync})이 따르는 계약(WD-78). 파사드 {@link PythonReadGrants} 가 현재 테넌트의 백엔드로 하나를 골라 같은
 * 메서드를 부른다.
 *
 * <p>두 구현이 똑같이 갖는 "데이터셋 → 테이블 찾기", "커밋 후 테이블 재동기화 예약", "회수 결과 → 좁히기 결과", "테넌트 동기화 잠금 잡기(대기·시도·묶인
 * 대기)" 는 정적 도우미({@link #syncDatasetTable}, {@link #scheduleTableSync}, {@link #narrowResultOf},
 * {@link #withTenantSyncLock})로 한 번만 둔다 — 한쪽만 고치면 RLS(새 트랜잭션)·실패 처리·잠금 격상 규칙이 갈라지기 때문이다. 백엔드는 잠금 원시
 * 동작({@link SyncLockOps})만 넘긴다.
 */
interface PythonReadGrantBackend {

  /** 테넌트 동기화 잠금을 잡는 방식(WD-644). */
  enum LockMode {
    /** 풀릴 때까지 기다린다 — 실행 준비(JIT)·전체 동기화·직접 부른 테이블 동기화. */
    WAIT,
    /** 기다리지 않고 시도만 한다 — 커밋 뒤 실행기의 처음 몇 번. */
    TRY,
    /** 주어진 시간만큼만 줄에 서서 기다린다 — 커밋 뒤 실행기의 그 뒤 시도·종료 비우기(시도형이 굶지 않게). */
    WAIT_BOUNDED;

    /** 실행기가 정한 잠금 대기 → 방식: 0 이면 시도형, 양수면 묶인 대기. */
    static LockMode forExecutorWait(Duration lockWait) {
      return lockWait.isZero() || lockWait.isNegative() ? TRY : WAIT_BOUNDED;
    }
  }

  /**
   * 백엔드별 테넌트 동기화 잠금 원시 동작(WD-644) — PG 판은 데이터 저장소 DSL(PG advisory), Tibero 판은 플랫폼 DB 의 {@code
   * PlatformLocks}(플랫폼이 PG 면 PG advisory, Tibero 면 {@code DBMS_LOCK} — WD-493 저장소별 잠금). 모두 현재 트랜잭션
   * 범위 잠금이다(트랜잭션이 끝나면 풀린다). 키는 {@link PythonReadGrantSync#syncLockKey} 다.
   */
  interface SyncLockOps {
    /** 풀릴 때까지 기다려 잡는다. */
    void lock(String key);

    /** 기다리지 않고 시도한다 — 쥔 쪽이나 기다리는 쪽이 있으면 false. */
    boolean tryLock(String key);

    /**
     * 최대 {@code timeout} 기다려 잡는다 — 넘기면 예외(PG: lock_not_available 55P03, 트랜잭션 중단 / Tibero: {@link
     * PlatformLockWaitTimeoutException}). 어느 쪽이든 {@link #isLockTimeout} 이 "바쁨" 으로 가른다.
     */
    void lock(String key, Duration timeout);
  }

  /**
   * 묶인 대기의 잠금 획득 문장이 시간을 넘겼다는 표시(WD-644 리뷰) — 잠금 획득에서만 던진다. 같은 55P03 이라도 잠금을 잡은 뒤 {@code work} 가 던진
   * 것(테이블 잠금 시간 초과 등)은 "바쁨" 이 아니라 동기화 실패이므로, SQLState 가 아니라 이 표시로 가른다.
   */
  final class LockWaitTimedOut extends RuntimeException {
    LockWaitTimedOut(Throwable cause) {
      super("테넌트 동기화 잠금 대기 시간 초과", cause);
    }
  }

  /**
   * 새 트랜잭션에서 현재 테넌트의 동기화 잠금을 {@code mode} 대로 잡고 {@code work} 를 돌린다 — 두 백엔드의 유일한 잠금 지점(WD-644). 잠금이
   * 바빠(시도 실패·묶인 대기 시간 초과) 일을 하지 못했으면 빈 값이다 — 그때 트랜잭션은 아무것도 바꾸지 않고 끝나 커넥션을 돌려준다. {@code work} 는 잠금을
   * 쥔 같은 트랜잭션 안에서 돈다. 그 밖의 예외(work 가 던진 55P03 포함)는 그대로 던진다.
   *
   * @param boundedWait {@link LockMode#WAIT_BOUNDED} 의 최대 대기 — 다른 방식에서는 쓰지 않는다(null 가능)
   */
  static <T> Optional<T> withTenantSyncLock(
      TransactionTemplate requiresNew,
      SyncLockOps ops,
      LockMode mode,
      Duration boundedWait,
      Supplier<T> work) {
    try {
      return requiresNew.execute(
          status -> {
            String key = PythonReadGrantSync.syncLockKey(TenantContext.require("PYTHON 읽기 권한 동기화"));
            switch (mode) {
              case WAIT -> ops.lock(key);
              case TRY -> {
                if (!ops.tryLock(key)) {
                  return Optional.<T>empty();
                }
              }
              case WAIT_BOUNDED -> {
                try {
                  ops.lock(key, boundedWait);
                } catch (RuntimeException e) {
                  // 잠금 획득 문장의 시간 초과만 "바쁨" 이다 — 표시 예외로 바꿔 트랜잭션을 되돌리게 던진다(중단된 트랜잭션은 이어 쓸 수 없다).
                  if (isLockTimeout(e)) {
                    throw new LockWaitTimedOut(e);
                  }
                  throw e;
                }
              }
            }
            return Optional.of(work.get());
          });
    } catch (LockWaitTimedOut e) {
      // 묶인 대기의 시간 초과 — 트랜잭션은 이미 롤백됐다(잠금 전이라 바뀐 것이 없다).
      return Optional.empty();
    }
  }

  /**
   * 커밋 뒤 실행기 경로의 묶음 동기화 결과(WD-644 리뷰).
   *
   * <p>묶음은 잠금 한 번 안에서 테이블마다 savepoint 로 나뉘어 돈다. 그래서 한 테이블의 실패(읽기 포함)는 그 테이블만 되돌리고, 나머지 테이블의 회수는 함께
   * 커밋된다. 실행기는 실패한 테이블만 다시 하거나 버린다 — 끝난 테이블을 다시 하지도, "버림" 으로 세지도 않는다.
   *
   * @param locked 잠금을 잡아 묶음을 돌았는가 — false 면 잠금이 바빠 아무것도 하지 않았다
   * @param failed 실패한 항목 → 그 예외(삽입 순서 유지). 여기 없는 항목은 커밋됐다
   */
  record BatchResult(boolean locked, Map<String, RuntimeException> failed) {

    /** 잠금이 바빠 아무것도 하지 않았다. */
    static final BatchResult BUSY = new BatchResult(false, Map.of());

    public BatchResult {
      failed = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(failed));
    }

    /** 잠금을 잡아 묶음을 돌았다 — {@code failed} 밖의 항목은 커밋됐다. */
    static BatchResult done(Map<String, RuntimeException> failed) {
      return new BatchResult(true, failed);
    }
  }

  /**
   * 묶인 대기의 잠금 획득이 시간을 넘겼는가 — 원인 사슬에 PG 의 lock_not_available(SQLState 55P03 — lock_timeout 초과)이나,
   * SQLState 가 없는 저장소(Tibero DBMS_LOCK 반환값 1, WD-493)의 {@link PlatformLockWaitTimeoutException} 이
   * 있으면 그렇다. 잠금 획득 문장에서 난 예외에만 부른다({@link #withTenantSyncLock}).
   */
  static boolean isLockTimeout(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
        return true;
      }
      if (t instanceof PlatformLockWaitTimeoutException) {
        return true;
      }
    }
    return false;
  }

  /**
   * 다시 해 볼 만한 실패인가(WD-644 리뷰) — 원인 사슬에 다음 중 하나가 있을 때만 그렇다.
   *
   * <ul>
   *   <li>Spring {@link TransientDataAccessException}·{@link RecoverableDataAccessException}, JDBC
   *       {@link SQLTransientException}(커넥션 풀 시간 초과 포함)·{@link SQLRecoverableException}
   *   <li>SQLState 클래스 08(연결), 40001·40P01(직렬화 실패·교착), 57P01(관리자 종료), 53xxx(자원 부족)
   * </ul>
   *
   * <p>그 밖의 DB 예외(문법·권한·제약 위반 등)와 프로그램 오류는 다시 해도 같으므로 영구 실패다 — 실행기가 곧바로 버리고 ERROR 로 남긴다. 예전처럼 모든
   * DataAccessException·SQLException 을 일시 실패로 보면 영구 오류가 재시도 횟수만큼 헛돌았다.
   */
  static boolean isTransient(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof TransientDataAccessException
          || t instanceof RecoverableDataAccessException
          || t instanceof SQLTransientException
          || t instanceof SQLRecoverableException) {
        return true;
      }
      if (t instanceof SQLException sql && isTransientSqlState(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  /** 일시 실패로 보는 SQLState — 08xxx·40001·40P01·57P01·53xxx. */
  private static boolean isTransientSqlState(String state) {
    if (state == null) {
      return false;
    }
    return state.startsWith("08")
        || state.startsWith("53")
        || "40001".equals(state)
        || "40P01".equals(state)
        || "57P01".equals(state);
  }

  /** PYTHON 실행 직전 준비 — 실행 슬롯 번호(1~10). 실패는 {@link PythonReadAccessException}. */
  int prepareForRun(Clearance runAs, int readRank);

  /** 현재 테넌트 전체 재동기화. */
  PythonReadGrantSync.SyncResult syncTenant();

  /** 등급 정의 변경 뒤 전체 재동기화(백엔드별로 실행 중 스크립트를 막는 방법이 다르다). */
  PythonReadGrantSync.SyncResult syncTenantForLevelsChange();

  /**
   * 커밋 직전 좁히기 결과(WD-624).
   *
   * @param confirmed 남는 권한을 모두 회수했거나 회수할 것이 없음을 확인했는가
   * @param exposedSlots 이 변경으로 그 테이블을 더는 읽으면 안 되는 슬롯(회수 실패 슬롯 포함) — 확인 못 했을 때 실행 중 스크립트를 막을 범위
   */
  record NarrowResult(boolean confirmed, Set<Integer> exposedSlots) {

    static NarrowResult confirmed(Set<Integer> exposedSlots) {
      return new NarrowResult(true, Set.copyOf(exposedSlots));
    }

    static NarrowResult unconfirmed(Set<Integer> exposedSlots) {
      return new NarrowResult(false, Set.copyOf(exposedSlots));
    }

    /** 대체 경로(막기)가 필요한가 — 확인 못 했고, 더는 읽으면 안 되는 슬롯이 있다(넓어지는 변경이면 필요 없다). */
    boolean needsFallback() {
      return !confirmed && !exposedSlots.isEmpty();
    }
  }

  /**
   * 데이터셋 등급 변경 트랜잭션의 커밋 직전, <b>그 트랜잭션에서</b> 데이터셋 테이블의 남는 슬롯 권한만 회수한다(WD-624 — 좁히기는 커밋 응답 전, 넓히기는 커밋
   * 뒤 실행기). 새 트랜잭션·새 플랫폼 커넥션을 열지 않는다. 파사드가 savepoint 안에서 부른다.
   */
  NarrowResult narrowDatasetInCurrentTx(long datasetId, Long fromLevelId, long toLevelId);

  /**
   * 커밋 직전 좁히기를 확인하지 못한 데이터셋 등급 변경의 뒷정리 — 실행기에서 커밋 뒤에 돈다(WD-624). 그 슬롯의 실행 중 스크립트를 막고 잠금 안에서 테이블을
   * 맞춘다(회수 포함).
   *
   * @param tokensInvalidated 커밋 직전에 스크립트 토큰 세대 번호를 이미 올렸는가(Tibero — 못 올렸으면 여기서 올린다)
   */
  void syncDatasetAfterUnconfirmedNarrowing(
      long datasetId, Set<Integer> exposedSlots, boolean tokensInvalidated);

  /** 데이터셋 하나의 테이블만 재동기화. */
  void syncDataset(long datasetId);

  /** 쓰기 경로 훅 — 커밋 후 전용 실행기에서(트랜잭션이 없으면 즉시) 그 테이블만 재동기화. 실패는 로그만. */
  void syncTableAfterCommit(String tableName);

  /**
   * 테이블 여러 개를 테넌트 동기화 잠금 한 번 안에서 동기화한다 — 커밋 뒤 실행기 전용(WD-644). 잠금 대기 0 이면 시도형, 양수면 묶인 대기. 테이블마다
   * savepoint 로 격리해, 한 테이블의 실패(읽기·쓰기)가 다른 테이블의 회수를 되돌리지 않게 하고 실패한 테이블만 결과에 싣는다(WD-644 리뷰).
   *
   * @return 잠금이 바빠 아무것도 하지 않았으면 {@link BatchResult#BUSY}, 아니면 실패한 테이블(없으면 빈 맵)
   */
  BatchResult syncTablesWith(List<String> tableNames, Duration lockWait);

  /**
   * 데이터셋 여러 개(id 문자열)의 테이블을 찾아 {@link #syncTablesWith} 로 한 번에 동기화한다 — 데이터셋 등급 변경의 커밋 뒤 실행기
   * 경로(WD-624 → WD-644). 리스너는 커밋 콜백에서 백엔드도 테이블 이름도 묻지 않으므로(바깥 커넥션 재사용 방지) 데이터셋 id 만 실행기에 싣고, 찾기는
   * 실행기 스레드에서 한다.
   *
   * @return 잠금이 바빠 아무것도 하지 않았으면 {@link BatchResult#BUSY}, 아니면 실패한 데이터셋 id(테이블 실패를 데이터셋으로 되짚은 것)
   */
  BatchResult syncDatasetsWith(List<String> datasetIds, Duration lockWait);

  /**
   * 데이터셋의 테이블 이름을 새 트랜잭션에서 찾아(dataset 은 RLS 테이블이라 트랜잭션=GUC 없이 읽으면 0행이다) 그 테이블을 동기화한다. 물리 테이블이 없는
   * 데이터셋(문서·파일형)은 할 일이 없다.
   */
  static <R> R syncDatasetTable(
      TransactionTemplate requiresNew,
      DatasetRepository datasetRepository,
      long datasetId,
      Function<String, R> syncTable) {
    // 플랫폼 테이블(dataset) 조회는 리포지토리로 — 데이터 저장소 DSL 로 읽으면 분리 설치에서 엉뚱한 DB 를 본다(WD-494).
    String table =
        requiresNew.execute(status -> datasetRepository.findTableNameById(datasetId).orElse(null));
    return table == null ? null : syncTable.apply(table);
  }

  /**
   * {@link #syncDatasetsWith} 공통부(WD-644) — 데이터셋들의 테이블 이름을 새 트랜잭션 하나에서 찾고(RLS 라 트랜잭션 필요, 찾기와 동기화는
   * 차례로라 커넥션은 한 번에 하나), 물리 테이블이 있는 것만 {@code syncTablesWith} 로 잠금 한 번에 동기화한다. 모두 테이블이 없으면 할 일이 없어 빈
   * 실패로 끝난다. 실패한 테이블은 그 테이블의 데이터셋 id 로 되짚어 돌려준다 — 실행기 항목이 데이터셋 id 라서 그 id 만 다시 하거나 버리게(WD-644 리뷰).
   */
  static BatchResult syncDatasetTablesWith(
      TransactionTemplate requiresNew,
      DatasetRepository datasetRepository,
      List<String> datasetIds,
      Duration lockWait,
      BiFunction<List<String>, Duration, BatchResult> syncTablesWith) {
    // 테이블 → 그 테이블을 가리키는 데이터셋 id 들(삽입 순서 유지).
    Map<String, List<String>> idsByTable =
        requiresNew.execute(
            status -> {
              Map<String, List<String>> found = new LinkedHashMap<>();
              for (String id : datasetIds) {
                datasetRepository
                    .findTableNameById(Long.parseLong(id))
                    .ifPresent(t -> found.computeIfAbsent(t, x -> new ArrayList<>()).add(id));
              }
              return found;
            });
    if (idsByTable == null || idsByTable.isEmpty()) {
      return BatchResult.done(Map.of());
    }
    BatchResult result = syncTablesWith.apply(List.copyOf(idsByTable.keySet()), lockWait);
    if (!result.locked()) {
      return result;
    }
    Map<String, RuntimeException> failedIds = new LinkedHashMap<>();
    result
        .failed()
        .forEach(
            (table, e) -> {
              for (String id : idsByTable.getOrDefault(table, List.of())) {
                failedIds.put(id, e);
              }
            });
    return BatchResult.done(failedIds);
  }

  /**
   * 회수 실패 결과(테이블 → 롤/계정 이름)에 나온 슬롯 번호들(WD-624). PG(슬롯 롤)·Tibero(저장소 계정)는 슬롯 → 이름 규칙만 다르므로 그것만 받는다.
   *
   * @param slotName 슬롯 k(1~10) → 그 슬롯의 롤/계정 이름
   */
  static Set<Integer> slotsOf(Map<String, Set<String>> revokeFailed, IntFunction<String> slotName) {
    Set<Integer> slots = new TreeSet<>();
    for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
      String name = slotName.apply(k);
      for (Set<String> names : revokeFailed.values()) {
        if (names.contains(name)) {
          slots.add(k);
        }
      }
    }
    return slots;
  }

  /**
   * 커밋 직전 회수({@code applyInTx(revokeOnly)}) 결과를 좁히기 결과로 바꾼다(WD-624, PG·Tibero 공통). 회수 실패가 없으면 확인됨.
   *
   * <p>회수 실패면 — savepoint 가 되돌리면 성공한 회수도 사라진다(파사드). 실제 ACL 이 이전 등급보다 넓게 어긋나 있었을 수 있으므로 이전 등급을 모르는
   * 것으로 보고 "새 등급에서 못 읽는 모든 슬롯" + 실패 슬롯을 막는다(드문 경로라 넓게).
   *
   * @param exposed 이전 → 새 등급으로 더는 읽으면 안 되는 슬롯
   * @param levelsAsc 회수 실패 때만 읽는 등급 목록(잠금 뒤 이 트랜잭션에서 다시 읽는다 — 회수 계산과 같은 스냅샷 규칙)
   * @param slotName 슬롯 k → 롤/계정 이름({@link #slotsOf})
   */
  static NarrowResult narrowResultOf(
      PythonReadGrantSync.SyncResult result,
      Set<Integer> exposed,
      Supplier<List<LevelPolicy>> levelsAsc,
      long toLevelId,
      IntFunction<String> slotName) {
    if (result.revokeFailed().isEmpty()) {
      return NarrowResult.confirmed(exposed);
    }
    Set<Integer> wider =
        new TreeSet<>(PythonReadSlots.slotsLosingAccess(levelsAsc.get(), null, toLevelId));
    wider.addAll(slotsOf(result.revokeFailed(), slotName));
    return NarrowResult.unconfirmed(wider);
  }

  /**
   * 커밋 뒤 실행기 경로의 여러 테이블 동기화 순서 — 이름 오름차순 중복 없이(WD-644 리뷰). 한 트랜잭션에서 테이블들을 차례로 맞추며 테이블 잠금을 트랜잭션 끝까지
   * 쥐므로, 순서를 정해 두어야 잠금 순서가 결정적이다(syncInTx 의 테이블 순서 규칙과 같은 이유).
   */
  static List<String> batchOrder(Collection<String> tables) {
    return List.copyOf(new TreeSet<>(tables));
  }

  /**
   * 쓰기 경로 훅 공통부 — 트랜잭션 동기화가 활성이면 커밋된 경우({@code STATUS_COMMITTED})에만 완료 후 전용 실행기에서 비동기로, 아니면 호출
   * 스레드에서 즉시 {@code syncTable} 을 부른다. 왜 실행기인지는 {@link PythonReadSyncExecutor} 참고.
   *
   * <p><b>실행기 경로(WD-644).</b> 테이블은 (백엔드, 테넌트) 작업에 항목으로 모이고, 실행기는 그 테넌트의 밀린 테이블을 <b>잠금 한 번에</b> 함께
   * 동기화한다(바쁜 테넌트가 테이블 수만큼 단일 스레드를 붙잡지 않게). 잠금은 처음 몇 번은 <b>시도만</b>({@link LockMode#TRY}), 그 뒤·종료
   * 비우기는 묶인 대기({@link LockMode#WAIT_BOUNDED})로 잡는다 — 바쁘면 아무 일도 하지 않고 트랜잭션을 끝내(커넥션 반납) BUSY 를 돌려주고,
   * 실행기가 적용될 때까지 다시 한다. 일시 실패({@link #isTransient})는 FAILED 로 몇 번 더 해 보고, 그 밖의 예외는 영구 실패로 ERROR 를
   * 남기고 버린다. 트랜잭션이 없는 즉시 경로는 호출 스레드에서 돌므로 지금처럼 잠금을 기다리는 {@code syncTable} 을 쓴다(실패는 로그만).
   *
   * <p>예약 단계 전체가 try 안이다 — 테넌트 컨텍스트 누락(require 실패)·동기화 등록 실패도 "로그만, 커밋을 막지 않는다" 계약에 든다. 미커밋 트랜잭션
   * 안에서 바로 동기화하면 새 트랜잭션이 아직 보이지 않는 데이터셋 행을 못 읽어 "없음" 으로 회수하므로 반드시 커밋 뒤에 돈다.
   *
   * @param executor 커밋 뒤 동기화를 돌릴 전용 실행기(PG·Tibero 공용)
   * @param logPrefix 로그 문구 앞머리(백엔드 구분, 예: {@code ""}·{@code "Tibero "})
   * @param syncTable 잠금을 기다리는 동기화 — 트랜잭션 없는 즉시 경로 전용
   * @param syncTablesWith 테이블들을 잠금 한 번에 동기화(잠금 대기 0 = 시도형, 양수 = 묶인 대기; 바빠서 아무 일 없으면 BUSY, 아니면 실패한
   *     테이블) — 실행기 경로 전용
   */
  static void scheduleTableSync(
      String tableName,
      Logger log,
      String logPrefix,
      PythonReadSyncExecutor executor,
      Consumer<String> syncTable,
      BiFunction<List<String>, Duration, BatchResult> syncTablesWith) {
    try {
      // 테넌트는 예약 시점(요청 스레드)에 잡는다 — 실행기 스레드에는 TenantContext 가 없다.
      long tenantId = TenantContext.require("PYTHON 읽기 권한 동기화 예약");
      // 실행기 경로의 한 번의 시도 — 테이블 묶음과 잠금 대기는 실행기가 정한다.
      PythonReadSyncExecutor.Attempt attempt =
          retryableAttempt(tenantId, log, logPrefix, syncTablesWith);
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCompletion(int status) {
                // 여기서 동기로 돌리면 바깥 커넥션을 쥔 채 두 번째 커넥션을 잡는다 — 실행기에 넘기기만 한다(포화·종료면 버림, 예외 없음).
                // 그룹이 (백엔드, 테넌트)라 같은 테넌트의 잇단 커밋은 한 작업으로 모여 잠금 한 번에 동기화된다.
                if (status == STATUS_COMMITTED) {
                  executor.submitRetryable(logPrefix + "tenant=" + tenantId, tableName, attempt);
                }
              }
            });
      } else {
        // 트랜잭션이 없으면 호출 스레드에서 바로 — 실행기 줄을 막지 않으므로 지금처럼 잠금을 기다린다(실패는 로그만).
        try {
          TenantContext.runScoped(tenantId, () -> syncTable.accept(tableName));
        } catch (RuntimeException e) {
          logFailure(log, logPrefix, tenantId, List.of(tableName), e);
        }
      }
    } catch (RuntimeException e) {
      log.warn("{}PYTHON 읽기 권한 동기화 예약 실패(다음 동기화에서 회복): table={}", logPrefix, tableName, e);
    }
  }

  /**
   * 커밋 뒤 실행기의 <b>재시도형</b> 시도(WD-644) — 항목 묶음과 잠금 대기(0 = 시도형, 양수 = 묶인 대기)를 {@code syncWith} 에 넘기고
   * 결과를 실행기 언어(DONE·BUSY·FAILED·PERMANENT_FAILURE·PartialFailure)로 바꾼다. 테이블 동기화 예약({@link
   * #scheduleTableSync})과 데이터셋 등급 변경 리스너(WD-624)가 함께 쓴다.
   *
   * <p>일부 항목만 실패하면(WD-644 리뷰) 그 항목만 실패 분류({@link #isTransient})대로 나눠 {@link PartialFailure} 로 알린다 —
   * 일시 실패는 WARN 후 실행기가 그 항목만 다시 하고, 영구 실패는 ERROR 후 그 항목만 버린다. 끝난 항목은 다시 하지 않는다.
   */
  static PythonReadSyncExecutor.Attempt retryableAttempt(
      long tenantId,
      Logger log,
      String logPrefix,
      BiFunction<List<String>, Duration, BatchResult> syncWith) {
    return (items, lockWait) ->
        attemptOnce(
            tenantId,
            items,
            log,
            logPrefix,
            () -> toAttemptResult(tenantId, log, logPrefix, syncWith.apply(items, lockWait)));
  }

  /** 묶음 결과 → 실행기 언어. 실패한 항목은 저마다 분류하고 로그를 남긴다(일시 실패 WARN, 영구 실패 ERROR). */
  private static AttemptResult toAttemptResult(
      long tenantId, Logger log, String logPrefix, BatchResult result) {
    if (!result.locked()) {
      return Outcome.BUSY;
    }
    if (result.failed().isEmpty()) {
      return Outcome.DONE;
    }
    List<String> transientFailed = new ArrayList<>();
    List<String> permanentFailed = new ArrayList<>();
    result
        .failed()
        .forEach(
            (item, e) -> {
              if (isTransient(e)) {
                logFailure(log, logPrefix, tenantId, List.of(item), e);
                transientFailed.add(item);
              } else {
                logPermanent(log, logPrefix, tenantId, List.of(item), e);
                permanentFailed.add(item);
              }
            });
    return new PartialFailure(transientFailed, permanentFailed);
  }

  /**
   * 커밋 뒤 실행기의 <b>대기형</b> 시도(WD-624 경로를 WD-644 실행기에 올릴 때) — 잠금 대기 값을 쓰지 않고 {@code work} 를 끝까지 돌린다(그
   * 안에서 잠금을 기다린다). 끊기 → 잠금 안 재확인처럼 바쁠 때마다 처음부터 되풀이하면 안 되는 일 전용이다. 그래도 실패 분류(일시 실패만 재시도·영구 실패는 ERROR
   * 후 버림)·테넌트 세우기·종료 비우기는 재시도형과 같다. BUSY 는 내지 않는다.
   */
  static PythonReadSyncExecutor.Attempt waitingAttempt(
      long tenantId, Logger log, String logPrefix, Runnable work) {
    return (items, lockWait) ->
        attemptOnce(
            tenantId,
            items,
            log,
            logPrefix,
            () -> {
              work.run();
              return Outcome.DONE;
            });
  }

  /**
   * 실행기 경로의 시도 한 번 — 테넌트를 세워 {@code sync} 를 부르고 그 결과를 돌려준다. 묶음 전체가 예외로 끝나면(잠금·커밋·연결 등) 일시 실패는
   * FAILED(WARN — 실행기가 몇 번 더 한다), 그 밖의 예외는 PERMANENT_FAILURE(ERROR — 다시 해도 같으므로 실행기가 곧바로 버리고 센다)다.
   * runScopedGet 은 finally 로 진입 전 테넌트로 되돌려 실행기의 다음 작업에 새지 않는다.
   */
  private static AttemptResult attemptOnce(
      long tenantId,
      List<String> tables,
      Logger log,
      String logPrefix,
      Supplier<AttemptResult> sync) {
    try {
      return TenantContext.runScopedGet(tenantId, sync::get);
    } catch (RuntimeException e) {
      if (isTransient(e)) {
        logFailure(log, logPrefix, tenantId, tables, e);
        return Outcome.FAILED;
      }
      logPermanent(log, logPrefix, tenantId, tables, e);
      return Outcome.PERMANENT_FAILURE;
    }
  }

  /** 영구 실패 ERROR — 묶음 전체·테이블 하나가 같은 문구를 쓴다. */
  private static void logPermanent(
      Logger log, String logPrefix, long tenantId, List<String> tables, RuntimeException e) {
    log.error(
        "{}PYTHON 읽기 권한 동기화 영구 실패 — 재시도하지 않고 버린다(실행 직전·일 1회 동기화가 회복): tenant={} tables={}",
        logPrefix,
        tenantId,
        tables,
        e);
  }

  /** 동기화 실패 WARN — 두 경로(실행기·즉시)가 같은 문구를 쓴다. */
  private static void logFailure(
      Logger log, String logPrefix, long tenantId, List<String> tables, RuntimeException e) {
    log.warn(
        "{}PYTHON 읽기 권한 동기화 실패(다음 동기화에서 회복): tenant={} tables={}", logPrefix, tenantId, tables, e);
  }
}
