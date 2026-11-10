package com.smartfirehub.securitylevel.pythonread;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.global.datastore.DataStoreConnectionBinding;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.datastore.DataStoreDbConfig;
import com.smartfirehub.global.platformdb.postgres.PostgresPlatformLocks;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PYTHON 읽기 슬롯 롤의 테이블 SELECT GRANT 를 계산값에 맞추는 <b>단일 진입점</b>(스펙 §4.3).
 *
 * <p><b>왜 런타임 롤(app_tenant)인가.</b> 데이터셋 테이블 소유자가 app_tenant 라(DataTableService 생성, V86 이관)
 * GRANT/REVOKE 를 할 수 있다. 스키마 USAGE 등 소유자 전용 권한은 프로비저너 몫이다(Task 2).
 *
 * <p><b>왜 차이만 적용하는가.</b> PYTHON 실행 직전마다 부르므로(JIT — 이벤트 유실·미연결에도 과권한 창이 없게) 변경 없는 경우의 비용이 카탈로그 조회 몇
 * 번이어야 한다. 실제 ACL 은 {@code aclexplode(pg_class.relacl)} 로 읽는다. 또 REVOKE 는 <b>남는 권한이 있을 때만</b> 한다 —
 * 부족한 GRANT 만 있는 테이블에 REVOKE 를 던지면, 런타임 롤이 소유하지 않은(권한도 없는) 옛 테이블에서 오류가 나 "회수 실패"로 오판되고 그 테넌트의
 * PYTHON 전체가 거부된다.
 *
 * <p><b>왜 새 트랜잭션인가.</b> security_level·dataset 은 RLS 테이블이라 트랜잭션(=GUC) 없이 읽으면 0행이고, 0행이면 "전부 회수"가
 * 된다. 호출부(비트랜잭션 러너·afterCommit 콜백)와 무관하게 항상 자기 트랜잭션을 연다. 같은 테넌트의 동기화는 advisory lock 으로 직렬화한다(동시
 * GRANT 의 XX000 "tuple concurrently updated" 방지 — TenantPipelineRoleProvisioner 주석의 같은 함정).
 */
@Slf4j
@Service
public class PythonReadGrantSync implements PythonReadGrantBackend {

  /**
   * 동기화 결과.
   *
   * @param levelsAsc 동기화에 쓴 등급 스냅샷(rank 오름차순) — prepareForRun 이 같은 스냅샷으로 슬롯을 계산해 계산 불일치 창을 없앤다
   * @param availableSlots 실제로 존재하는 슬롯 롤 번호(실행 준비 동기화면 실행 슬롯만 본다)
   * @param changedTables 요청한 범위 안에서 권한을 바꾼 테이블 수(두 백엔드 같은 규칙, WD-679)
   * @param revokeFailed 남는 권한을 회수하지 못한 테이블 → 과권한이 남았을 수 있는 슬롯 롤 이름들. prepareForRun 은 <b>실행 슬롯
   *     롤</b>이 여기 들어 있을 때만 실행을 거부한다(다른 슬롯 롤의 과권한은 이 실행이 쓰지 않는 롤이라 무관). GRANT 실패는 과소권한(안전)이라 로그만 남긴다
   * @param runSlot 실행 준비 동기화(prepareForRun)에서 같은 등급 스냅샷으로 계산한 실행 슬롯(범위 밖이면 그 값 그대로), 그 밖의 동기화는 0
   * @param revoked REVOKE 문장이 성공한 테이블 → 회수한 슬롯 롤 이름들(WD-664 7) — 확인된 커밋 직전 좁히기 뒤 그 슬롯의 세션만 끊는 데 쓴다.
   *     PG 판만 채운다(Tibero 는 끊을 세션이 없어 — 스펙 §5.1 — 비워 둔다)
   */
  public record SyncResult(
      List<LevelPolicy> levelsAsc,
      Set<Integer> availableSlots,
      int changedTables,
      Map<String, Set<String>> revokeFailed,
      int runSlot,
      Map<String, Set<String>> revoked) {

    /** 회수 기록 없이 만든다(Tibero 판·아무것도 바꾸지 않은 결과). */
    public SyncResult(
        List<LevelPolicy> levelsAsc,
        Set<Integer> availableSlots,
        int changedTables,
        Map<String, Set<String>> revokeFailed,
        int runSlot) {
      this(levelsAsc, availableSlots, changedTables, revokeFailed, runSlot, Map.of());
    }

    /** 회수 실패 테이블 이름들(롤 구분 없이). */
    public Set<String> revokeFailedTables() {
      return revokeFailed.keySet();
    }
  }

  /** 테이블 잠금 대기 상한 — 생성 직후 컬럼 DDL 은 짧고, 이보다 긴 대기는 다음 동기화에 맡긴다. */
  private static final String TABLE_LOCK_TIMEOUT = "5s";

  private final DSLContext dsl;

  /**
   * 슬롯 롤 세션 끊기 장치(WD-624) — 런타임 롤(app_tenant)은 pg_signal_backend 멤버도, 슬롯 롤 멤버도 아니어서 슬롯 롤 세션을 끊을 수
   * 없다(실측). 그래서 소유자 연결을 가진 프로비저너가 끊되, 그 대기(소유자 풀 크기 1·세션당 최대 5초)가 플랫폼 커넥션·동기화 잠금을 쥔 채 길어지지 않게 전용
   * 스레드에서 돌리고 여기서는 상한만큼만 기다린다.
   */
  private final PythonReadSessionTerminator terminator;

  /**
   * 미확인 표식 저장소(WD-701 4차) — 데이터 저장소 표(모든 인스턴스가 본다) + 끊기 장치의 메모리 대비 표식. 쓰기 연결은 {@link #borrowWithin}
   * 으로 상한 안에서 빌린다.
   */
  private final PythonReadUnconfirmedMarks unconfirmedMarks;

  private final SecurityLevelRepository levelRepository;
  // 플랫폼 테이블(dataset) 조회 — 데이터 저장소 DSL 이 아니라 리포지토리로 읽는다(WD-494).
  private final DatasetRepository datasetRepository;
  private final TransactionTemplate requiresNew;

  /**
   * 커밋 뒤 묶음 동기화에서 테이블 하나를 감싸는 플랫폼 savepoint 템플릿(PROPAGATION_NESTED, WD-644 리뷰) — 테이블 하나의 플랫폼 읽기
   * 실패(등급·데이터셋 행)가 묶음 트랜잭션 전체를 aborted 로 만들어 다른 테이블의 회수까지 되돌리지 않게 한다. 데이터 저장소 쪽은 {@link
   * #inTableSavepoint} 가 따로 감싼다.
   */
  private final TransactionTemplate tableSavepoint;

  /** 커밋 뒤 테이블 동기화를 돌리는 전용 실행기 — 커밋 콜백 스레드가 커넥션 2개를 쥐지 않게 한다(WD-594). */
  private final PythonReadSyncExecutor afterCommitExecutor;

  /**
   * 실행기·실행 준비가 세션 끊기를 기다리는 상한 — 이 기다림 동안에는 커넥션도 테넌트 동기화 잠금도 쥐지 않는다(WD-624, 남을 막지 않는다). 넘기면 넓히지
   * 않는다(전체 동기화) / 실행을 거부한다(실행 준비).
   */
  static final Duration TERMINATE_WAIT = Duration.ofSeconds(10);

  /** 커밋 직전 좁히기가 테넌트 동기화 잠금을 기다리는 상한 — 사용자 트랜잭션의 커넥션을 쥐고 기다리므로 짧게 둔다(WD-624). */
  static final Duration NARROW_LOCK_WAIT = Duration.ofSeconds(3);

  /** 등급 정의 변경 전체 동기화의 "끊기 → 잠금 안 재확인" 시도 횟수 — 끊은 뒤에도 슬롯 세션이 계속 다시 붙으면 이번에는 넓히지 않는다. */
  static final int LEVELS_SYNC_ATTEMPTS = 3;

  /**
   * 분리 설치(데이터 저장소가 플랫폼 DB 와 다른 DB)인가 — 커밋 직전 좁히기를 데이터 저장소 연결에서 따로 커밋한다({@link
   * #narrowDatasetInCurrentTx}, WD-701 3차).
   */
  private final boolean separateDataStore;

  /**
   * 데이터 저장소 풀(WD-701 3차) — 분리 설치의 커밋 직전 좁히기가 이 트랜잭션에 데이터 저장소 연결이 아직 묶이지 않았을 때 연결 하나를 따로 빌려 REVOKE 를
   * <b>플랫폼 커밋 전에</b> 커밋한다({@link #narrowOnOwnDataStoreConnection}). 분리하지 않은 설치에서는 쓰지 않는다(플랫폼 풀 별칭).
   */
  private final DataSource dataStoreDataSource;

  /**
   * 이 인스턴스가 쓰는 커밋 직전 시간 상한(WD-701 3차) — 운영은 {@link PreCommitDeadline#DEFAULT_BOUNDS}, 단위 테스트는 짧게
   * 바꾼다.
   */
  private volatile PreCommitDeadline.Bounds preCommitBounds = PreCommitDeadline.DEFAULT_BOUNDS;

  /** 테스트 전용 — 분리 설치 커밋 직전 좁히기의 예산 초과를 몇 초 안에 재현하려고 상한을 짧게 바꾼다. */
  void preCommitBoundsForTest(PreCommitDeadline.Bounds bounds) {
    this.preCommitBounds = bounds;
  }

  /**
   * txManager 는 @Primary TenantAwareTransactionManager — 트랜잭션 시작 때 TenantContext 를 RLS GUC 로 주입한다.
   */
  public PythonReadGrantSync(
      @DataStoreDb DSLContext dsl,
      @DataStoreDb DataSource dataStoreDataSource,
      PythonReadSessionTerminator terminator,
      SecurityLevelRepository levelRepository,
      DatasetRepository datasetRepository,
      PlatformTransactionManager txManager,
      PythonReadSyncExecutor afterCommitExecutor,
      @Value("${" + DataStoreDbConfig.SEPARATE_PROPERTY + ":false}") boolean separateDataStore) {
    this.dsl = dsl;
    this.dataStoreDataSource = dataStoreDataSource;
    this.afterCommitExecutor = afterCommitExecutor;
    this.terminator = terminator;
    this.unconfirmedMarks = new PythonReadUnconfirmedMarks(this::borrowForMark, terminator);
    this.levelRepository = levelRepository;
    this.datasetRepository = datasetRepository;
    this.separateDataStore = separateDataStore;
    this.requiresNew = new TransactionTemplate(txManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.tableSavepoint = new TransactionTemplate(txManager);
    this.tableSavepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  }

  /**
   * 기동 점검(WD-713) — 데이터 저장소의 세션 수 함수 소유자가 남의 세션 시작 시각을 볼 수 있는지(superuser 또는 pg_read_all_stats)
   * 확인하고, 못 보면 WARN 한 줄을 남긴다. 기동은 막지 않는다(점검 실패도 WARN). 판정·문구는 {@link
   * PythonReadUnconfirmedMarks#warnIfOwnerCannotReadStats} 에 있다.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void checkSessionCountOwnerOnStartup() {
    PythonReadUnconfirmedMarks.warnIfOwnerCannotReadStats(dsl);
  }

  /** 현재 테넌트 전체 재동기화. 실패는 예외(호출부가 로그/실패를 정한다). */
  @Override
  public SyncResult syncTenant() {
    return lockedSync(null, null);
  }

  /**
   * 등급 정의 변경(추가·수정·삭제·순서) 뒤 테넌트 전체 재동기화 — 넓히는 GRANT 를 커밋하기 전에 그 테넌트 슬롯 롤의 활성 세션이 없게 한다(CR2).
   *
   * <p><b>왜 끊는가.</b> 슬롯 k 는 "rank 오름차순 k 번째 등급까지"다. 순서 변경·삭제(위치 당김)·허용 목록 해제는 같은 슬롯 롤에 새 등급 테이블을
   * GRANT 한다. PostgreSQL 은 매 쿼리마다 권한을 보므로, 옛 위치로 슬롯을 받아 실행 중이던 스크립트(최대 30분)가 다음 쿼리부터 자격 밖 등급을 읽는다.
   * 이벤트는 무엇이 바뀌었는지 싣지 않아(이름만 바꾼 UPDATED 포함) 등급 정의 변경마다 끊는다 — 드문 관리 작업이라 수용한다. 커밋 콜백이 응답 전에 이미 한 번
   * 끊었고, 여기서 다시 보는 이유는 커밋 직전에 옛 등급으로 슬롯을 고른 실행 준비가 그 뒤에 스크립트를 접속시켰을 수 있기 때문이다.
   *
   * <p><b>왜 잠금 밖에서 끊고 잠금 안에서는 확인만 하는가(WD-624).</b> 끊기는 소유자 풀(크기 1)·세션당 최대 5초를 기다린다. 그 기다림을 테넌트 동기화
   * 잠금과 플랫폼 커넥션을 쥔 채 하면, 그 잠금을 기다리는 실행 준비들이 각자 플랫폼 커넥션을 쥔 채 함께 멈춘다. 그래서 (1) 커넥션·잠금 없이 끊고 ({@link
   * #TERMINATE_WAIT} 상한, 넘기면 예외 = 넓히지 않음), (2) 새 트랜잭션에서 잠금을 잡은 뒤 <b>넓어질 슬롯</b>의 세션이 하나도 없을 때만
   * 넓힌다(WD-701 4차 — {@link #applyLevelsChangeUnderLock}). 있으면 이 트랜잭션은 아무것도 바꾸지 않고 끝내고 그 슬롯만 끊은 뒤 다시
   * 본다({@link #LEVELS_SYNC_ATTEMPTS} 번). 그래도 남으면 예외 — 이번에는 넓히지 않는다(fail-closed). 넓히지 못해도 기능은 유지된다:
   * 실행 준비가 자기 슬롯을 계산값대로(넓히기 포함) 맞춘 뒤 스크립트를 띄운다.
   */
  @Override
  public SyncResult syncTenantForLevelsChange() {
    long tenantId = TenantContext.require("등급 정의 변경 전체 동기화");
    return applyLevelsChangeUnderLock(tenantId);
  }

  /**
   * 등급 정의 변경 동기화 본체(WD-701 4차) — 잠금 안에서 <b>새로 넓어질 슬롯</b>을 계산하고 그 슬롯의 세션이 하나도 없을 때만 적용한다. 있으면 아무것도
   * 바꾸지 않고 잠금을 놓은 뒤 그 슬롯만 <b>잠금·커넥션 없이</b> 끊고 다시 본다({@link #LEVELS_SYNC_ATTEMPTS} 번 끊고도 남으면 예외 =
   * 넓히지 않음).
   *
   * <p><b>왜 넓어질 슬롯만 보는가(WD-701 4차 — 8).</b> 예전에는 테넌트 슬롯 전부를 먼저 끊고, 잠금 안에서 어떤 슬롯이든 세션이 있으면 다시 끊었다(최대
   * 3번). 그런데 커밋 뒤 정상으로 시작한 실행은 실행 준비가 자기 슬롯을 <b>이미 새 정의로 넓힌 뒤</b> 붙는다 — 그 슬롯은 더 넓어질 것이 없다. 위험은 옛
   * 정의로 슬롯을 받은 세션이 살아 있는 채 그 슬롯이 넓어지는 것뿐이므로, 넓어질 슬롯만 확인·끊으면 정상 실행을 거듭 끊지 않는다. 좁아지거나 그대로인 슬롯의 옛 세션은
   * 새로 읽게 되는 것이 없다(PG 는 쿼리마다 권한을 본다 — 좁힌 테이블은 다음 쿼리부터 거부). 커밋 직후의 첫 끊기는 커밋 콜백이 이미 슬롯 전부에 했다({@link
   * #terminateSlotSessionsAfterCommit}).
   *
   * <p><b>실행 준비가 먼저 넓힌 슬롯도 다시 본다(WD-701 4차 리뷰 3, 내가 정한 것).</b> 커밋 직전에 옛 정의로 준비한 스크립트가 커밋 뒤 첫 실행
   * 준비(그 슬롯을 넓힘) <b>다음에</b> 접속할 수 있다 — PG 는 Tibero 와 달리 스크립트 토큰 무효화가 없어, 이 작업이 슬롯 전부를 끊던 예전에는 그 세션을
   * 이 작업이 끊었다. 그래서 실행 준비는 REDEFINED 표식을 지우는 대신 REDEFINED_APPLIED 로 바꾸고, 이 작업은 넓어질 슬롯 ∪ 실행 준비가 넓힌
   * 슬롯을 확인한다. 커밋 뒤 손대지 않았고 넓어질 것도 없는 슬롯만 끊지 않는다 — 실행 준비가 넓힌 슬롯의 정상 세션은 예전처럼 끊길 수 있다(받아들인 값).
   *
   * <p><b>왜 시작 시각으로 거르지 않는가(내가 정한 것).</b> 커밋 직전에 옛 정의로 슬롯을 받은 실행 준비의 스크립트가 커밋·표식 <b>뒤</b>에 접속할 수 있다
   * — 시작 시각으로는 그 세션을 가를 수 없다. 넓어질 슬롯에서는 세션이 하나도 없어야 넓힌다(fail-closed).
   *
   * <p><b>표식 정리.</b> 적용하면 모든 슬롯이 커밋된 정의로 맞춰졌으므로, 잠금 안에서 등급을 읽기 <b>전</b>에 데이터 저장소 시계로 읽은 시각 이하의 등급
   * 정의 변경 표식(REDEFINED)을 같은 트랜잭션에서 지운다 — 그 뒤 커밋된 정의 변경의 표식은 남는다.
   */
  private SyncResult applyLevelsChangeUnderLock(long tenantId) {
    Set<Integer> toKill = Set.of();
    for (int attempt = 0; attempt <= LEVELS_SYNC_ATTEMPTS; attempt++) {
      if (!toKill.isEmpty()) {
        terminator.terminateWithin(tenantId, toKill, TERMINATE_WAIT);
      }
      AtomicReference<Set<Integer>> busy = new AtomicReference<>(Set.of());
      SyncResult result =
          requiresNew.execute(
              status -> {
                // 대기 잠금(WD-644 의 WAIT) — 시도형으로 바꾸지 않는다(드문 경로라 실행기 줄을 잠시 막는 쪽을 받아들인다).
                lockOps.lock(syncLockKey(tenantId));
                OffsetDateTime checkedAt = PythonReadUnconfirmedMarks.dataStoreNow(dsl);
                AclPlan plan = planAcl(dsl, tenantId, null, null);
                // 확인할 슬롯 = 이번에 넓어질 슬롯 ∪ 커밋 뒤 실행 준비가 이미 넓힌 슬롯(REDEFINED_APPLIED, 리뷰 3). 뒤쪽은 커밋 직전에 옛
                // 정의로 준비한 스크립트가 그 실행 준비 뒤에 늦게 붙었을 수 있어 다시 본다. 둘 다 아닌 슬롯은 끊지 않는다.
                Set<Integer> check = new TreeSet<>(widenedSlots(plan));
                check.addAll(unconfirmedMarks.appliedSlots(dsl, tenantId, checkedAt));
                if (!check.isEmpty() && slotSessionsExist(tenantId, check)) {
                  busy.set(check);
                  return null; // 확인할 슬롯에 세션 — 잠금을 놓고 그 슬롯만 끊는다
                }
                SyncResult applied = applyInTx(dsl, tenantId, null, null, false);
                unconfirmedMarks.clearRedefinedUpTo(dsl, tenantId, checkedAt);
                return applied;
              });
      if (result != null) {
        return result;
      }
      toKill = busy.get();
      log.info("넓어질 슬롯에 세션이 있어 끊고 다시 본다(tenant={} slots={} 시도 {})", tenantId, toKill, attempt + 1);
    }
    // 포기 사유를 예외 문구에 담는다(WD-713) — 실행기의 영구 실패 ERROR 가 이 문구를 싣고, 리스너의 버림 ERROR 가 남는 표식을 알린다.
    throw new IllegalStateException(
        "PYTHON 슬롯 롤 세션이 "
            + LEVELS_SYNC_ATTEMPTS
            + "번 끊은 뒤에도 계속 다시 붙어 넓히는 GRANT 를 이번에는 걸지 않았다 — 미확인 표식(REDEFINED·REDEFINED_APPLIED)이 남는다(tenant="
            + tenantId
            + " 세션이 남은 slots="
            + toKill
            + ")");
  }

  /**
   * 실행 준비의 미확인 표식 경로(WD-624 → WD-701 4차) — 실행 슬롯의 표식이 요구하는 세션(기준 시각 전, 또는 전부)을 <b>잠금·커넥션 없이</b> 끊고,
   * 잠금 안에서 <b>잠금 뒤 등급으로 다시 고른 실행 슬롯</b>의 표식을 다시 읽어 그 세션이 남지 않았을 때만 슬롯을 맞춘다. 남았으면 아무것도 바꾸지 않고 잠금을 놓은
   * 뒤 그만큼만 다시 끊는다({@link #LEVELS_SYNC_ATTEMPTS} 번, 그래도 남으면 예외 = 실행 거부).
   *
   * <p><b>실행 슬롯만 본다(WD-664 8).</b> 다른 슬롯의 표식은 이 실행이 쓰지 않는 롤이라 무관하다 — 그 슬롯의 실행 준비가 본다.
   *
   * <p><b>시작 시각(WD-701 4차).</b> 회수·끊기 미확인 표식(STALE_SESSION)이면 표식 <b>전에</b> 시작한 세션만 끊고 확인한다 — 표식 뒤에
   * 시작한 세션은 실행 준비가 그 슬롯을 커밋된 등급으로 맞춘 뒤 붙었거나 회수가 반영된 뒤라 끊지 않는다(예전에는 다 끊고 10초 기다린 뒤 실패해 무관한 실행까지
   * 거부했다). 등급 정의 변경 표식·메모리 대비 표식이면 예전처럼 세션 전부다({@link PythonReadUnconfirmedMarks}).
   *
   * <p><b>표식 지우기.</b> 확인과 맞추기가 끝나고 실행 슬롯 롤에 회수 실패가 없으면, 읽은 번호 그대로인 표식만 같은 잠금 트랜잭션에서 지운다(그 사이 다른
   * 인스턴스가 다시 단 표식은 남는다). 메모리 대비 표식은 커밋 뒤 순번이 같을 때만 지운다.
   *
   * <p><b>거부 로그(WD-713).</b> 끝내 맞추지 못하고 실행을 거부하는 지점(시도 상한·끊기 실패·잠금 안 세션 확인 실패)에서 WARN 한 줄에 테넌트·실행
   * 슬롯·표식 종류·기준 시각·사유를 남긴다 — 예전에는 게이트가 예외를 감싸기만 해 운영 로그에 아무것도 없었다. 경합으로 생기는 예상된 거부라 ERROR 가 아니라
   * WARN 이고, 거부된 실행마다 한 줄이다(시도마다의 INFO 는 그대로).
   *
   * @param firstKill 잠금 전에 처음 끊을 범위(잠금 밖 추정 실행 슬롯 → 기준 시각, 비면 건너뛴다)
   * @param firstMarks 잠금 밖에서 읽은 추정 실행 슬롯의 표식(없으면 null) — 잠금 안에서 다시 읽기 전에 거부되면 로그에 쓴다
   */
  private SyncResult prepareUnderMarks(
      long tenantId,
      int readRank,
      Map<Integer, OffsetDateTime> firstKill,
      PythonReadUnconfirmedMarks.SlotMarks firstMarks) {
    Map<Integer, OffsetDateTime> toKill = firstKill;
    // 거부 로그용 — 마지막으로 본 실행 슬롯과 그 표식(잠금 안에서 다시 읽으면 갱신).
    AtomicReference<Integer> lastSlot =
        new AtomicReference<>(firstKill.isEmpty() ? null : firstKill.keySet().iterator().next());
    AtomicReference<PythonReadUnconfirmedMarks.SlotMarks> lastMarks =
        new AtomicReference<>(firstMarks);
    for (int attempt = 1; attempt <= LEVELS_SYNC_ATTEMPTS; attempt++) {
      try {
        terminator.terminateWithin(tenantId, toKill, TERMINATE_WAIT);
      } catch (IllegalStateException e) {
        logPrepareRefused(
            tenantId, lastSlot.get(), lastMarks.get(), "슬롯 세션 끊기 실패: " + e.getMessage());
        throw e;
      }
      AtomicReference<Map<Integer, OffsetDateTime>> again = new AtomicReference<>(Map.of());
      AtomicReference<Runnable> afterCommit = new AtomicReference<>(() -> {});
      SyncResult result;
      try {
        result =
            requiresNew.execute(
                status -> {
                  lockOps.lock(syncLockKey(tenantId));
                  // 잠금 뒤 읽은 등급으로 실행 슬롯을 정한다(applyInTx 와 같은 스냅샷 규칙) — 표식도 잠금 안에서 다시 읽는다.
                  int runSlot = PythonReadSlots.slotFor(levelRepository.findAll(), readRank);
                  PythonReadUnconfirmedMarks.SlotMarks marks =
                      unconfirmedMarks.read(dsl, tenantId).get(runSlot);
                  lastSlot.set(runSlot);
                  lastMarks.set(marks);
                  if (marks != null) {
                    Map<Integer, OffsetDateTime> scope = new TreeMap<>();
                    scope.put(runSlot, marks.cutoff());
                    if (slotSessionsExist(tenantId, scope)) {
                      again.set(scope);
                      return null; // 표식이 요구하는 세션이 남았다 — 잠금을 놓고 그만큼 다시 끊는다
                    }
                  }
                  SyncResult applied = applyInTx(dsl, tenantId, null, readRank, false);
                  if (marks != null && !runSlotOverGranted(tenantId, runSlot, applied)) {
                    unconfirmedMarks.clearAfterRun(dsl, tenantId, runSlot, marks);
                    afterCommit.set(
                        () -> unconfirmedMarks.clearMemoryAfterRun(tenantId, runSlot, marks));
                  }
                  return applied;
                });
      } catch (RuntimeException e) {
        // 잠금 안 세션 확인(fh_python_read_sessions_before)·표식 읽기·맞추기 실패 — 표식이 있었을 때만 표식 때문에 거부된 것으로 남긴다
        // (표식이 없는 일반 실패는 게이트·호출부가 다룬다).
        if (lastMarks.get() != null) {
          logPrepareRefused(tenantId, lastSlot.get(), lastMarks.get(), "잠금 안 세션 확인·맞추기 실패: " + e);
        }
        throw e;
      }
      if (result != null) {
        afterCommit.get().run();
        return result;
      }
      toKill = again.get();
      log.info("끊은 뒤 슬롯 세션이 남아 다시 끊는다(tenant={} 범위={} 시도 {})", tenantId, toKill, attempt);
    }
    logPrepareRefused(
        tenantId,
        lastSlot.get(),
        lastMarks.get(),
        LEVELS_SYNC_ATTEMPTS + "번 끊은 뒤에도 표식이 요구하는 세션이 남아");
    throw new IllegalStateException(
        "PYTHON 슬롯 롤 세션이 계속 남아 실행 슬롯을 이번에는 맞추지 않았다(tenant=" + tenantId + " 범위=" + toKill + ")");
  }

  /**
   * 실행 준비가 미확인 표식 때문에 실행을 거부하는 지점의 WARN(WD-713) — 테넌트·실행 슬롯·표식 종류·기준 시각·사유. 표식 뒤에 시작한 세션까지 계속 남는
   * 것처럼 보이면 세션 확인 함수 소유자에게 통계 열람 권한이 없는 것일 수 있다(모든 세션의 시작 시각이 NULL 로 보여 표식 전으로 세진다 — fail-closed
   * 장애).
   */
  private static void logPrepareRefused(
      long tenantId, Integer slot, PythonReadUnconfirmedMarks.SlotMarks marks, String reason) {
    log.warn(
        "PYTHON 실행 준비 거부 — 미확인 표식 슬롯의 세션을 정리하지 못했다: {}(tenant={} slot={} 표식={} 기준시각={}). 표식 뒤에 시작한 세션도 계속"
            + " 남는다면 세션 확인 함수(fh_python_read_sessions_before) 소유자의 superuser·pg_read_all_stats 권한을 확인한다",
        reason,
        tenantId,
        slot,
        marks == null ? "?" : marks.describeKinds(),
        marks == null ? "?" : (marks.cutoff() == null ? "전부" : marks.cutoff()));
  }

  /** 실행 슬롯 롤에 회수 실패(과권한)가 남았는가 — 남았으면 표식을 지우지 않는다(게이트가 실행도 거부한다). */
  private static boolean runSlotOverGranted(long tenantId, int runSlot, SyncResult result) {
    if (runSlot < 1 || runSlot > TenantPipelineRole.PYTHON_READ_SLOTS) {
      return true;
    }
    String role = TenantPipelineRole.pythonReadRoleName(tenantId, runSlot);
    return result.revokeFailed().values().stream().anyMatch(roles -> roles.contains(role));
  }

  /** 그 테넌트의 주어진 슬롯 롤 세션이 지금 있는가. 런타임 롤도 남의 세션 행의 롤 이름은 볼 수 있다(쿼리 내용 등은 가려진다). */
  private boolean slotSessionsExist(long tenantId, Set<Integer> slots) {
    return slotSessionsExist(tenantId, PythonReadSessionTerminator.allSessions(slots));
  }

  /**
   * 그 테넌트의 슬롯마다 기준 시각 전에 시작한({@code backend_start < 기준}, null 이면 전부) 슬롯 롤 세션이 지금 있는가(WD-701 4차). 기준
   * 시각은 같은 데이터 저장소 시계로 적은 표식 시각이다.
   */
  private boolean slotSessionsExist(long tenantId, Map<Integer, OffsetDateTime> startedBefore) {
    if (startedBefore.isEmpty()) {
      return false;
    }
    List<String> roles = new ArrayList<>();
    List<OffsetDateTime> cutoffs = new ArrayList<>();
    startedBefore.forEach(
        (slot, cutoff) -> {
          roles.add(TenantPipelineRole.pythonReadRoleName(tenantId, slot));
          cutoffs.add(cutoff);
        });
    // 런타임 롤(app_tenant)에게는 남의 세션 backend_start 가 NULL 로 보인다(실측, 리뷰 2) — pg_stat_activity 를 직접 비교하면
    // "표식 전 세션 없음" 으로 오판한다(fail-open). 소유자 권한으로 세는 SECURITY DEFINER 함수(V159·데이터 저장소 V2)를 부른다.
    // 소유자 풀(끊기가 쓰는 크기 1)을 잠금 안에서 기다리지 않게 연결이 아니라 함수다. 시작 시각이 끝내 안 보이면 함수가 표식 전으로
    // 센다(fail-closed). 배열이 NULL 이거나 길이가 다르면 함수가 오류를 던진다 — 그대로 올라가 실행 거부·작업 재시도가 된다.
    Object count =
        dsl.fetchValue(
            "select public.fh_python_read_sessions_before({0}::text[], {1}::timestamptz[])",
            DSL.val(roles.toArray(new String[0])), DSL.val(cutoffs.toArray(new OffsetDateTime[0])));
    return count != null && ((Number) count).longValue() > 0;
  }

  /**
   * 커밋 직후 그 테넌트의 주어진 슬롯 롤 세션 끊기를 <b>요청</b>한다 — 커밋 콜백(회수 미확인 대체 경로·등급 정의 변경)이 부른다(WD-624 → WD-664
   * 9). GRANT 는 건드리지 않는다.
   *
   * <p><b>왜 커밋 뒤에 끊는가.</b> 넓어지는 GRANT 는 전용 실행기에서 나중에 돈다. 그 사이 옛 등급으로 슬롯을 받아 실행 중이던 세션이 살아 있으면 (1)
   * 좁아진 슬롯의 테이블을 계속 읽고 (2) 다른 실행의 준비(JIT)가 같은 슬롯을 새 기준으로 넓히면 자격 밖까지 읽는다. 끊긴 스크립트는 다시 실행되면 JIT 가 커밋된
   * 등급으로 자기 슬롯을 맞춘 뒤에 접속한다.
   *
   * <p><b>왜 기다리지 않는가(WD-664 9, 설계 결정 Q2 A).</b> 커밋 콜백은 바깥 플랫폼 커넥션을 쥔 채 돈다 — 예전처럼 상한(3초)만큼 기다리면 동시
   * 커밋 N 개가 플랫폼 풀 커넥션 N 개를 그만큼 쥐었다. 이제 슬롯마다 미확인 표식을 <b>먼저</b> 달고 끊기 요청만 넣은 뒤 돌아온다. 표식은 그 슬롯의 실행 준비가
   * 넓히기 전에 먼저 끊게 하고, 못 끊으면 실행을 거부하게 한다(fail-closed). 받아들인 값: 커밋 응답 시점에 세션이 끊겼다는 보장은 없다.
   *
   * <p><b>표식은 데이터 저장소에(WD-701 4차).</b> 상한({@link PythonReadUnconfirmedMarks#MARK_CONNECT_WAIT}) 안에서
   * 따로 빌린 연결로 표에 곧바로 커밋한다 — 모든 인스턴스의 실행 준비가 본다. 표식은 실행 준비(그 슬롯을 확인하고 맞춘 뒤)나 뒤따르는 실행기 작업(등급 정의 변경
   * 동기화·회수 미확인 뒷정리)이 지운다 — 커밋 직후 끊기 성공만으로는 지우지 않는다(끊기 스레드는 데이터 저장소 연결을 쓰지 않는다). 쓰지 못하면(데이터 저장소 장애 —
   * 대체 경로가 바로 그런 때 돈다) 이 인스턴스 메모리 표식을 요청 전에 달고({@link PythonReadSessionTerminator#markAndRequest})
   * 실행기에서 다시 쓴다 — 그동안 다른 인스턴스는 이 표식을 못 본다(남는 틈).
   */
  public void terminateSlotSessionsAfterCommit(
      long tenantId, Set<Integer> slots, boolean slotRedefined) {
    if (slots.isEmpty()) {
      return;
    }
    PythonReadUnconfirmedMarks.Kind kind =
        slotRedefined
            ? PythonReadUnconfirmedMarks.Kind.REDEFINED
            : PythonReadUnconfirmedMarks.Kind.STALE_SESSION;
    // 표식을 끊기 요청 전에 데이터 저장소에 단다(WD-701 4차 — 모든 인스턴스의 실행 준비가 본다). 못 쓰면 이 인스턴스 메모리 표식을
    // 요청 전에 달고(markAndRequest), 실행기가 데이터 저장소 표식을 다시 쓴다.
    if (unconfirmedMarks.persist(tenantId, slots, kind)) {
      terminator.requestTermination(tenantId, slots);
    } else {
      terminator.markAndRequest(tenantId, slots);
      retryPersistMark(tenantId, slots, kind);
    }
  }

  /**
   * 데이터 저장소에 표식을 쓰지 못했을 때 실행기에서 다시 쓴다(WD-701 4차) — 일시 실패로 던져 재시도 상한까지 한다. 늦게 쓴 표식은 시각이 더 늦어 실행 준비가
   * 더 많은 세션을 "표식 전" 으로 본다(안전한 쪽). 등록 실패는 ERROR 만(메모리 표식은 이미 달렸다).
   */
  private void retryPersistMark(
      long tenantId, Set<Integer> slots, PythonReadUnconfirmedMarks.Kind kind) {
    try {
      afterCommitExecutor.submitRetryable(
          "python-read-mark t" + tenantId + " kind=" + kind + " slots=" + slots,
          kind.name(),
          PythonReadGrantBackend.waitingAttempt(
              tenantId,
              log,
              "미확인 표식 다시 쓰기 ",
              () -> {
                if (!unconfirmedMarks.persist(tenantId, slots, kind)) {
                  throw new TransientDataAccessResourceException(
                      "PYTHON 슬롯 미확인 표식 쓰기 실패 — 다시 한다(tenant=" + tenantId + ")");
                }
              }),
          true,
          // 포기 지점(재시도 소진·영구 실패·종료, WD-713) — 프로세스 메모리 로그만(실행기 콜백 규칙: 짧게, DB 없이).
          dropped -> logMarkPersistGivenUp(tenantId, slots, kind, "실행기 다시 쓰기를 끝내 하지 못함"));
    } catch (RuntimeException e) {
      log.error("PYTHON 슬롯 미확인 표식 다시 쓰기 등록 실패(tenant={} slots={})", tenantId, slots, e);
      logMarkPersistGivenUp(tenantId, slots, kind, "실행기 다시 쓰기 등록 실패");
    }
  }

  /**
   * 데이터 저장소 표식 쓰기를 포기했다는 ERROR(WD-713) — 시도마다의 쓰기 실패 로그와 달리 "이제 다시 쓰지 않는다" 는 끝 지점을 한 줄로 남긴다. 결과는 이
   * 인스턴스 메모리 표식만 남는 상태다: 이 인스턴스의 실행 준비는 그 슬롯을 계속 막지만, 다른 API 인스턴스의 실행 준비는 표식을 보지 못한다(롤링 업데이트 중 남는
   * 틈). 메모리 표식은 이 인스턴스가 재기동하면 사라진다.
   */
  private void logMarkPersistGivenUp(
      long tenantId, Set<Integer> slots, PythonReadUnconfirmedMarks.Kind kind, String reason) {
    log.error(
        "PYTHON 슬롯 미확인 표식을 데이터 저장소에 끝내 쓰지 못했다({}) — 이 인스턴스 메모리 표식만 남아 다른 API 인스턴스의 실행 준비는 이 표식을 보지"
            + " 못한다. 데이터 저장소 연결을 확인한다(tenant={} slots={} kind={})",
        reason,
        tenantId,
        slots,
        kind);
  }

  /**
   * 확인된 커밋 직전 회수 뒤, <b>실제로 회수한 슬롯</b>의 세션 끊기를 요청만 한다(WD-664 7) — 기다리지 않고 표식도 달지 않는다. 회수가 이미 커밋돼 새
   * 실행은 옳은 권한으로 붙으므로 실행 준비를 막을 필요가 없고, 회수 전에 시작한 긴 쿼리·서버 쪽 커서만 끊으면 된다(PG 는 권한을 쿼리 시작 때만 본다). 실패하면
   * 부르는 쪽이 {@link #terminateSlotSessionsOrThrow} 로 실행기에서 다시 한다.
   *
   * @return 끊은 세션 수로 완료되는 결과(실패면 예외로 완료)
   */
  public CompletableFuture<Integer> requestSlotSessionTermination(
      long tenantId, Set<Integer> slots) {
    return terminator.requestTermination(tenantId, slots);
  }

  /**
   * 슬롯 세션을 끊고 {@link #TERMINATE_WAIT} 까지 기다린다 — 실행기 작업 전용(커넥션·잠금을 쥐지 않은 채 부른다). 끊지 못하면 {@link
   * PythonReadGrantBackend.SessionKillFailed}(일시 실패, WD-664 1b)를 던져 실행기가 재시도 상한까지 다시 하게 한다.
   */
  public void terminateSlotSessionsOrThrow(long tenantId, Set<Integer> slots) {
    try {
      killAllAndClearStale(tenantId, slots);
    } catch (IllegalStateException e) {
      throw new PythonReadGrantBackend.SessionKillFailed(
          "PYTHON 슬롯 롤 세션 끊기 실패 — 다시 한다(tenant=" + tenantId + " slots=" + slots + ")", e);
    }
  }

  /**
   * 실행기 전용(커넥션·잠금 없이 부른다) — 슬롯 세션을 시작 시각과 무관하게 모두 끊고, 끊기 <b>전</b>에 데이터 저장소 시계로 읽은 시각 이하의
   * STALE_SESSION 표식을 지운다(WD-701 4차). 그 표식이 요구한 "표식 전 세션" 이 모두 끊겼기 때문이다. 표식 정리 실패는 WARN 만 — 남은 표식은
   * 다음 실행 준비가 확인하고 지운다(끊을 세션이 없으니 곧바로). 끊기 실패는 {@link IllegalStateException}.
   */
  private void killAllAndClearStale(long tenantId, Set<Integer> slots) {
    if (slots.isEmpty()) {
      return;
    }
    OffsetDateTime before = null;
    try {
      before = PythonReadUnconfirmedMarks.dataStoreNow(dsl);
    } catch (RuntimeException e) {
      log.warn("데이터 저장소 시계 읽기 실패 — 끊은 뒤 표식을 지우지 않는다(tenant={}): {}", tenantId, e.toString());
    }
    terminator.terminateWithin(tenantId, slots, TERMINATE_WAIT);
    if (before != null) {
      try {
        unconfirmedMarks.clearStaleUpTo(dsl, tenantId, slots, before);
      } catch (RuntimeException e) {
        log.warn("끊은 뒤 미확인 표식 지우기 실패 — 다음 실행 준비가 지운다(tenant={}): {}", tenantId, e.toString());
      }
    }
  }

  /**
   * 끊기 요청 없이 슬롯에 미확인 표식만 단다(WD-701 3차) — 확인된 회수 뒤 끊기 재시도가 실행기에서 모두 버려졌을 때(일시 실패 소진·영구 실패·포화·종료)
   * 리스너가 부른다. 회수 전에 시작한 긴 쿼리를 끊었다는 확인이 끝내 없으므로, 그 슬롯의 다음 실행 준비가 넓히기 전에 먼저 끊고 못 끊으면 실행을 거부하게
   * 한다(fail-closed). 표식은 그 뒤 성공한 끊기가 지운다.
   */
  public void markSlotsUnconfirmed(long tenantId, Set<Integer> slots) {
    if (slots.isEmpty()) {
      return;
    }
    // 회수는 이미 커밋됐다 — 슬롯의 뜻은 그대로라 표식 전에 시작한 세션만 문제다(STALE_SESSION, WD-701 4차).
    PythonReadUnconfirmedMarks.Kind kind = PythonReadUnconfirmedMarks.Kind.STALE_SESSION;
    if (!unconfirmedMarks.persist(tenantId, slots, kind)) {
      terminator.markUnconfirmed(tenantId, slots);
      retryPersistMark(tenantId, slots, kind);
    }
  }

  /** sinceMillis 이후 이 테넌트의 슬롯 롤 세션을 끊었는가 — 러너가 실패 문구를 고르는 데만 쓴다. */
  public boolean slotSessionsTerminatedSince(long tenantId, long sinceMillis) {
    return terminator.terminatedSince(tenantId, sinceMillis);
  }

  /** 테이블 하나만 재동기화(생성·클론·맞바꿈 직후 — RENAME 맞바꿈은 ACL 을 잃는다). */
  public SyncResult syncTable(String tableName) {
    return lockedSync(tableName, null);
  }

  /**
   * 테이블 여러 개를 테넌트 동기화 잠금 <b>한 번</b> 안에서 차례로 동기화한다(WD-644 리뷰) — 커밋 뒤 실행기 전용. 잠금 대기가 0 이면 시도만, 양수면
   * 그만큼 묶인 대기로 잡는다. 잠금이 바쁘면 아무 일도 하지 않고 곧바로 트랜잭션을 끝내(커넥션 반납) BUSY 를 돌려준다. 실행기가 나중에 다시 부른다.
   *
   * <p><b>테이블마다 savepoint(WD-644 리뷰).</b> applyInTx 의 문장 savepoint 는 GRANT/REVOKE·테이블 잠금만 감싸고, 그
   * 앞뒤의 읽기(등급· 데이터셋 행·pg_roles·슬롯 권한 조회)는 감싸지 않는다. PG 는 문장 하나가 실패하면 트랜잭션 전체가 aborted 가 되므로, 테이블 B 의
   * 읽기 실패 하나가 이미 적용한 테이블 A 의 회수까지 커밋하지 못하게 만들었다. 그래서 테이블 하나의 동기화 전체를 savepoint 로 감싸 실패하면 그 테이블만
   * 되돌리고, 다른 테이블은 이어서 맞춘 뒤 함께 커밋한다. 읽기를 묶음 앞에서 한 번만 하는 방식은 테이블별 읽기(데이터셋 행·슬롯 권한)가 남아 충분하지 않고, 테이블마다
   * 트랜잭션을 나누면 트랜잭션 범위의 테넌트 잠금이 테이블마다 풀려 다시 다퉈야 하므로 savepoint 를 골랐다. 연결이 끊기는 등 트랜잭션 자체가 죽으면 마지막 커밋이
   * 실패해 묶음 전체가 예외(실행기가 일시 실패로 다시 한다)로 끝난다 — 커밋되지 않은 것을 끝났다고 보고하지 않는다.
   *
   * @return 잠금이 바빠 건너뛰었으면 BUSY, 아니면 실패한 테이블 → 예외(없으면 빈 맵)
   */
  @Override
  public BatchResult syncTablesWith(List<String> tableNames, Duration lockWait) {
    return PythonReadGrantBackend.withTenantSyncLock(
            requiresNew,
            lockOps,
            PythonReadGrantBackend.LockMode.forExecutorWait(lockWait),
            lockWait,
            () -> {
              Map<String, RuntimeException> failed = new LinkedHashMap<>();
              // 잠금 안에서 표식 슬롯을 한 번 읽는다(재검토 1) — 표식이 남은 슬롯은 이 묶음에서 넓히지 않는다.
              Set<Integer> noWiden = markedSlotsUnderLock();
              for (String table : PythonReadGrantBackend.batchOrder(tableNames)) {
                try {
                  inTableSavepoint(() -> syncInTx(table, null, noWiden));
                } catch (RuntimeException e) {
                  // 이 테이블만 되돌려졌다 — 다른 테이블은 이어서 맞추고, 이 테이블은 실행기가 분류해 다시 하거나 버린다.
                  failed.put(table, e);
                }
              }
              return BatchResult.done(failed);
            })
        .orElse(BatchResult.BUSY);
  }

  /**
   * 테이블 하나의 동기화를 savepoint 로 감싼다(WD-644 리뷰) — 실패하면 그 테이블의 변경만 되돌리고 예외를 그대로 던진다. 플랫폼 트랜잭션은 NESTED
   * 템플릿({@link #tableSavepoint})이, 분리 설치의 데이터 저장소 연결은 이름 있는 SAVEPOINT 가 맡는다(분리하지 않으면 같은 연결이라 NESTED
   * 로 충분하다). applyInTx 안의 문장 savepoint({@code python_read_sync})와 이름을 달리해 서로의 되돌리기 범위가 섞이지 않게 한다.
   */
  private void inTableSavepoint(Runnable work) {
    tableSavepoint.executeWithoutResult(
        status -> {
          if (!separateDataStore) {
            work.run();
            return;
          }
          dsl.execute("SAVEPOINT python_read_sync_table");
          try {
            work.run();
            dsl.execute("RELEASE SAVEPOINT python_read_sync_table");
          } catch (RuntimeException e) {
            dsl.execute("ROLLBACK TO SAVEPOINT python_read_sync_table");
            throw e;
          }
        });
  }

  /**
   * 데이터셋 등급 변경의 커밋 뒤 실행기 경로(WD-624 리스너 → WD-644 실행기) — 데이터셋들의 테이블을 찾아 {@link #syncTablesWith} 로 잠금
   * 한 번에 맞춘다. 공통부는 {@link PythonReadGrantBackend#syncDatasetTablesWith}.
   */
  @Override
  public BatchResult syncDatasetsWith(List<String> datasetIds, Duration lockWait) {
    return PythonReadGrantBackend.syncDatasetTablesWith(
        requiresNew, datasetRepository, datasetIds, lockWait, this::syncTablesWith);
  }

  /**
   * 잠금을 기다려 잡은 뒤 동기화한다 — 커밋 뒤 실행기의 테이블 묶음을 뺀 경로(전체·테이블 하나·실행 준비)의 공통 진입점(WD-644). 등급 정의 변경 전체 동기화와
   * 미확인 표식이 있는 실행 준비는 끊기 → 잠금 안 재확인 순서가 필요해 {@link #prepareUnderMarks} 로 간다(WD-624).
   */
  private SyncResult lockedSync(String onlyTable, Integer runRank) {
    // 대기형(WAIT)은 빈 값을 내지 않는다는 불변식을 이름 붙은 검사로 묶는다 — 깨지면 원인을 알리는 IllegalStateException(WD-694).
    return PythonReadGrantBackend.requireWaitModeResult(
        PythonReadGrantBackend.withTenantSyncLock(
            requiresNew,
            lockOps,
            PythonReadGrantBackend.LockMode.WAIT,
            null,
            () -> syncInTx(onlyTable, runRank, markedSlotsUnderLock())));
  }

  /**
   * 잠금을 잡은 트랜잭션 안에서 미확인 표식이 남은 슬롯을 읽는다(WD-701 4차 재검토 1) — 일반 동기화(전체·테이블·커밋 뒤 실행기 묶음)는 이 슬롯을 <b>넓히지
   * 않는다</b>(회수만). 표식은 "그 슬롯에 옛 기준 세션이 남았을 수 있다" 는 뜻이라, 세션 확인 없이 넓히면 그 세션이 자격 밖까지 읽는다(등급 정의 변경 작업이
   * 재시도를 다 쓰고 실패했거나 아직 돌지 않았을 때 매일 전체 동기화·syncTable 이 그 빈틈을 열었다). 표식 슬롯을 넓히는 것은 세션을 확인하는 실행
   * 준비({@link #prepareUnderMarks})와 등급 정의 변경 작업({@link #syncTenantForLevelsChange})뿐이다.
   *
   * <p>{@link PythonReadUnconfirmedMarks.Kind#REDEFINED_APPLIED} 도 넓히지 않는다 — 실행 준비가 실행 슬롯만 확인해 넓힌
   * 뒤에도, 커밋 직전에 옛 정의로 준비한 스크립트가 늦게 붙었을 수 있어 등급 정의 변경 작업이 다시 확인하는 슬롯이다(그 확인 전에 일반 동기화가 새로 낮아진 테이블까지
   * 넓히면 늦은 세션이 읽는다). 실행 준비는 이 종류를 보지 않으므로 실행은 막히지 않는다. 읽기가 실패하면 savepoint 로 되돌려 트랜잭션은 살리고(회수는 계속해야
   * 한다) 모든 슬롯을 넓히지 않는다(fail-closed).
   */
  private Set<Integer> markedSlotsUnderLock() {
    long tenantId = TenantContext.require("PYTHON 읽기 표식 읽기");
    Set<Integer> marked = new TreeSet<>();
    boolean ok =
        inSavepoint(
            dsl,
            () -> {
              marked.addAll(unconfirmedMarks.read(dsl, tenantId).keySet());
              marked.addAll(
                  unconfirmedMarks.appliedSlots(
                      dsl, tenantId, PythonReadUnconfirmedMarks.dataStoreNow(dsl)));
            });
    if (!ok) {
      log.warn("미확인 표식 읽기 실패 — 이번 동기화는 모든 슬롯을 넓히지 않는다(tenant={})", tenantId);
      Set<Integer> all = new TreeSet<>();
      for (int slot = 1; slot <= TenantPipelineRole.PYTHON_READ_SLOTS; slot++) {
        all.add(slot);
      }
      return all;
    }
    return marked;
  }

  /** 데이터셋 하나 — 테이블 이름을 찾아 syncTable. 물리 테이블이 없는 데이터셋(문서·파일형)은 할 일이 없다. */
  @Override
  public void syncDataset(long datasetId) {
    PythonReadGrantBackend.syncDatasetTable(
        requiresNew, datasetRepository, datasetId, this::syncTable);
  }

  /**
   * 커밋 직전 좁히기를 확인하지 못한 데이터셋 등급 변경의 뒷정리(실행기, WD-624) — 그 테이블을 더는 읽으면 안 되는 슬롯의 세션을 <b>커넥션·잠금 없이</b>
   * 끊은 뒤, 잠금 안에서 테이블을 계산값으로 맞춘다(회수 포함).
   *
   * <p><b>왜 끊고 나서 회수하는가.</b> 커밋 직후 한 번 끊었어도, 커밋 직전에 잠금 안에서 옛 등급으로 슬롯을 맞춘 실행 준비가 그 뒤에 스크립트를 접속시켰을 수
   * 있다(그 실행의 GRANT 는 새 등급 기준으로 남는 권한이다). 여기서 다시 끊고, 잠금 안의 회수로 그 뒤에 붙은 세션도 다음 쿼리부터 막는다(PG 는 매 쿼리 권한을
   * 본다). 끊기 기다림을 잠금 안에서 하지 않는 이유는 {@link #syncTenantForLevelsChange} 와 같다. 끊기가 실패해도 회수는 한다(회수는 좁히기라
   * 늘 안전).
   *
   * <p><b>회수가 커밋된 뒤 한 번 더 끊는다.</b> 권한 확인은 쿼리 시작 때 한 번이라, 회수 커밋 전에 시작한 긴 쿼리 하나는 회수 뒤에도 끝까지 돈다. 회수 커밋
   * 뒤에 끊으면 그 전에 붙은 세션은 모두 끊기고, 그 뒤의 쿼리는 모두 거부된다 — 빈틈이 닫힌다. 회수 실패가 남으면 ERROR(그 슬롯의 새 실행은 실행 준비가 다시
   * 맞춘다). 이 두 번째 끊기가 실패하면 {@link PythonReadGrantBackend.SessionKillFailed}(일시 실패)로 끝나 실행기가 다시
   * 한다(WD-664 1b).
   */
  @Override
  public void syncDatasetAfterUnconfirmedNarrowing(
      long datasetId, Set<Integer> exposedSlots, boolean tokensInvalidated) {
    long tenantId = TenantContext.require("데이터셋 좁히기 뒷정리");
    terminateQuietly(tenantId, exposedSlots);
    SyncResult result =
        PythonReadGrantBackend.syncDatasetTable(
            requiresNew, datasetRepository, datasetId, this::syncTable);
    if (result == null) {
      return; // 물리 테이블이 없는 데이터셋 — 회수할 권한이 없다
    }
    Set<Integer> again = new TreeSet<>(exposedSlots);
    Set<Integer> left = slotsOf(tenantId, result.revokeFailed());
    if (!left.isEmpty()) {
      log.error(
          "데이터셋 좁히기 뒷정리: 회수 실패가 남았다(tenant={} dataset={} slots={})", tenantId, datasetId, left);
      again.addAll(left);
    }
    // 회수 커밋 뒤 — 커밋 전에 시작한 쿼리까지 끊는다. 이 끊기는 삼키지 않는다(WD-664 1b): 실패하면 일시 실패로 던져 실행기가 재시도 상한까지
    // 다시 하게 한다(회수는 멱등). 삼키면 작업이 DONE 으로 끝나 회수 커밋 전에 시작한 긴 쿼리가 끝까지 돈다.
    try {
      killAllAndClearStale(tenantId, again);
    } catch (IllegalStateException e) {
      throw new PythonReadGrantBackend.SessionKillFailed(
          "데이터셋 좁히기 뒷정리: 회수 뒤 PYTHON 슬롯 롤 세션 끊기 실패 — 다시 한다(tenant="
              + tenantId
              + " dataset="
              + datasetId
              + " slots="
              + again
              + ")",
          e);
    }
  }

  /**
   * 분리 설치에서 확인된 커밋 직전 회수가 플랫폼보다 먼저 커밋된 데이터셋 등급 변경의 뒷정리(실행기, WD-701 3차 리뷰 B1) — 잠금 안에서 그 테이블을 <b>커밋된
   * 새 등급</b>으로 다시 맞추고(회수 포함), 커밋 직전에 회수한 슬롯과 이번에 다시 회수한 슬롯의 세션을 끊는다.
   *
   * <p><b>왜.</b> 테넌트 동기화 잠금이 데이터 저장소 회수와 함께 플랫폼 커밋 전에 풀려, 그 틈에 잠금을 기다리던 동기화가 아직 커밋 안 된 옛 등급으로 회수한
   * 슬롯에 다시 GRANT 하고 스크립트를 붙였을 수 있다({@link #narrowOnSeparateDataStore}). 플랫폼 커밋 뒤라 이 재동기화는 새 등급을 읽어
   * 그 GRANT 를 거둔다. 끊기를 회수 <b>뒤</b>에 하므로 그 전에 붙은 세션은 모두 끊기고 뒤의 쿼리는 거부된다.
   *
   * <p><b>왜 테넌트 전체에서 차이 슬롯을 끊지 않는가.</b> 회수 미확인 뒷정리({@link #syncDatasetAfterUnconfirmedNarrowing})처럼
   * 차이 슬롯을 모두 끊으면, 회수할 권한이 없던 새 테이블(클론 상속·자동 상향)에서도 그 슬롯 세션을 테넌트 전체에서 끊는 가용성 회귀(WD-664 5)가 돌아온다.
   * 그래서 실제로 권한이 있었던 — 커밋 직전에 회수했거나 이번에 다시 회수한(되살아난) — 슬롯만 끊는다. 회수 실패가 남으면 그 슬롯도 끊고 ERROR 다. 끊지 못하면
   * {@link PythonReadGrantBackend.SessionKillFailed}(일시 실패)로 실행기가 다시 한다.
   */
  public void syncDatasetAfterSeparateNarrowing(long datasetId, Set<Integer> revokedSlots) {
    long tenantId = TenantContext.require("데이터셋 분리 설치 좁히기 뒷정리");
    SyncResult result =
        PythonReadGrantBackend.syncDatasetTable(
            requiresNew, datasetRepository, datasetId, this::syncTable);
    Set<Integer> kill = new TreeSet<>(revokedSlots);
    if (result != null) {
      Set<Integer> regranted = slotsOf(tenantId, result.revoked());
      if (!regranted.isEmpty()) {
        log.warn(
            "데이터셋 분리 설치 좁히기 뒷정리: 커밋 틈에 옛 등급으로 되살아난 권한을 다시 회수했다(tenant={} dataset={} slots={})",
            tenantId,
            datasetId,
            regranted);
      }
      kill.addAll(regranted);
      Set<Integer> left = slotsOf(tenantId, result.revokeFailed());
      if (!left.isEmpty()) {
        log.error(
            "데이터셋 분리 설치 좁히기 뒷정리: 회수 실패가 남았다(tenant={} dataset={} slots={})",
            tenantId,
            datasetId,
            left);
        kill.addAll(left);
      }
    }
    if (kill.isEmpty()) {
      return; // 끊을 슬롯 없음 — 권한이 원래 없었고 되살아나지도 않았다
    }
    try {
      killAllAndClearStale(tenantId, kill);
    } catch (IllegalStateException e) {
      throw new PythonReadGrantBackend.SessionKillFailed(
          "데이터셋 분리 설치 좁히기 뒷정리: PYTHON 슬롯 롤 세션 끊기 실패 — 다시 한다(tenant="
              + tenantId
              + " dataset="
              + datasetId
              + " slots="
              + kill
              + ")",
          e);
    }
  }

  /** 끊기를 요청하고 상한만큼 기다린다 — 실패는 ERROR 로그만(회수 앞의 첫 끊기 전용 — 뒤의 회수·두 번째 끊기가 다음 방어선을 이어 간다). */
  private void terminateQuietly(long tenantId, Set<Integer> slots) {
    try {
      terminator.terminateWithin(tenantId, slots, TERMINATE_WAIT);
    } catch (IllegalStateException e) {
      log.error("PYTHON 슬롯 롤 세션 끊기 실패(tenant={} slots={})", tenantId, slots, e);
    }
  }

  /** 회수 실패 결과(테이블 → 슬롯 롤 이름)에 나온 슬롯 번호들 — {@link PythonReadGrantBackend#slotsOf}. */
  private static Set<Integer> slotsOf(long tenantId, Map<String, Set<String>> revokeFailed) {
    return PythonReadGrantBackend.slotsOf(
        revokeFailed, k -> TenantPipelineRole.pythonReadRoleName(tenantId, k));
  }

  /**
   * 데이터셋 등급 변경을 커밋하기 <b>직전</b>, 데이터셋 테이블의 남는 슬롯 권한만 회수한다(WD-624). GRANT 는 걸지 않는다 — 넓어지는 쪽은 커밋 뒤 전용
   * 실행기의 {@link #syncDataset} 몫이다. 파사드가 플랫폼 savepoint 안에서 부르므로 여기서 난 예외는 이 좁히기만 되돌리고 발행 트랜잭션을 깨뜨리지
   * 않는다({@link PythonReadGrants#narrowDatasetInCurrentTx}).
   *
   * <p><b>같은 DB 설치 — 같은 트랜잭션.</b> 새 트랜잭션(REQUIRES_NEW)을 열면 요청 하나가 플랫폼 커넥션 2개를 쥐어 동시 변경 때 풀이 멈춘다. 같은
   * 트랜잭션이면 커넥션이 하나뿐이고, 아직 커밋 전인 새 등급을 그대로 읽어 계산하며, REVOKE 가 등급 변경과 <b>함께</b> 커밋된다 — 커밋 응답이 나가는 순간
   * 실행 중 세션의 다음 쿼리부터 막힌다.
   *
   * <p><b>분리 설치 — 데이터 저장소에서 플랫폼 커밋 전에 따로 커밋(WD-701 3차, 설계 결정 Q1 A).</b> 예전에는 분리 설치면 늘 "확인 못 함" 으로
   * 대체 경로(커밋 뒤 비동기 회수)로 갔다 — 커밋 응답이 나간 뒤에도 슬롯이 그 테이블을 읽을 수 있었다. 이제 {@link
   * #narrowOnSeparateDataStore} 가 데이터 저장소 연결에서 회수하고, 그 회수가 <b>플랫폼 커밋보다 먼저</b> 데이터 저장소에 커밋되게 한다. 시간
   * 예산({@link PreCommitDeadline}, Tibero 커밋 직전 좁히기와 같은 장치·같은 값)을 넘기거나 접속하지 못하면 "확인 못 함" 으로 대체 경로에
   * 맡긴다.
   *
   * <p><b>왜 방향(올림·내림)을 따지지 않는가.</b> 회수 대상은 "실제 ACL − 새 등급 기준 계산값" 이라 넓어지는 변경이면 저절로 빈다. 결과에는 확인 여부와
   * 함께 "더는 읽으면 안 되는 슬롯"({@link PythonReadSlots#slotsLosingAccess} + 회수 실패 슬롯)을 실어, 확인 못 했을 때 막을 범위를
   * 그 슬롯으로만 좁힌다.
   *
   * <p><b>확인 못 함으로 다루는 경우.</b> 잠금을 {@link #NARROW_LOCK_WAIT} 안에 못 잡음(막을 슬롯에 지금 실제로 SELECT 를 가진 슬롯을
   * 더한다 — WD-664 5, {@link #exposedWithCurrentHolders}), 회수 실패, 분리 설치의 시간 예산 초과·접속 실패(새 등급에서 못 읽는
   * 모든 슬롯).
   *
   * @throws IllegalStateException 실제 트랜잭션 밖에서 불렀을 때(파사드가 "확인 못 함" 으로 바꾼다)
   */
  @Override
  public NarrowResult narrowDatasetInCurrentTx(long datasetId, Long fromLevelId, long toLevelId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("데이터셋 등급 좁히기는 등급 변경 트랜잭션 안에서만 부른다");
    }
    long tenantId = TenantContext.require("데이터셋 등급 좁히기");
    List<LevelPolicy> levels = levelRepository.findAll();
    Set<Integer> exposed = PythonReadSlots.slotsLosingAccess(levels, fromLevelId, toLevelId);
    String table = datasetRepository.findTableNameById(datasetId).orElse(null);
    if (table == null) {
      return NarrowResult.confirmed(exposed); // 물리 테이블이 없는 데이터셋(문서·파일형) — 회수할 권한이 없다
    }
    if (separateDataStore) {
      return narrowOnSeparateDataStore(tenantId, datasetId, table, exposed, levels, toLevelId);
    }
    return narrowWith(
        dsl, tenantId, datasetId, table, exposed, levels, toLevelId, NARROW_LOCK_WAIT);
  }

  /**
   * 주어진 데이터 저장소 연결(같은 DB 설치면 발행 트랜잭션의 DSL, 분리 설치면 {@link #narrowOnSeparateDataStore} 가 고른 연결)의 현재
   * 트랜잭션에서 테넌트 동기화 잠금을 잡고 그 테이블의 남는 권한만 회수한다. 커밋·되돌리기는 부르는 쪽 몫이다.
   *
   * @param lockWait 잠금 대기 상한(분리 설치면 남은 예산으로 줄인 값)
   */
  private NarrowResult narrowWith(
      DSLContext ds,
      long tenantId,
      long datasetId,
      String table,
      Set<Integer> exposed,
      List<LevelPolicy> levels,
      long toLevelId,
      Duration lockWait) {
    if (!tryLockTenantSync(ds, tenantId, lockWait)) {
      Set<Integer> block =
          exposedWithCurrentHolders(ds, tenantId, table, exposed, levels, toLevelId);
      log.warn(
          "데이터셋 등급 좁히기: 테넌트 동기화 잠금을 {} 안에 얻지 못했다(tenant={} dataset={} 막을 슬롯={})",
          lockWait,
          tenantId,
          datasetId,
          block);
      return NarrowResult.unconfirmed(block);
    }
    // 회수 실패 시 넓혀 막을 슬롯은 잠금 뒤 applyInTx 가 이미 읽은 등급 스냅샷으로 계산한다(WD-664 10) — 같은 트랜잭션의 같은 값이라
    // 결과는 같고, 커밋 직전 경로(사용자 트랜잭션의 커넥션을 쥔 채)의 등급 재조회를 한 번 줄인다.
    PythonReadGrantSync.SyncResult narrowed = applyInTx(ds, tenantId, table, null, true);
    return PythonReadGrantBackend.narrowResultOf(
        narrowed,
        exposed,
        narrowed::levelsAsc,
        toLevelId,
        k -> TenantPipelineRole.pythonReadRoleName(tenantId, k));
  }

  /**
   * 잠금을 못 잡아 회수를 확인하지 못했을 때 막을 슬롯(WD-664 5) — 이전 → 새 등급 차이({@code exposed})에 <b>지금 그 테이블에 실제로
   * SELECT 를 가진 슬롯 중 새 등급에서 못 읽는 슬롯</b>을 더한다. 예전에는 차이만 막아, ACL 이 이전 등급보다 넓게 어긋나 있으면(손으로 건 GRANT·놓친
   * 회수) 그 슬롯을 막지 못했다.
   *
   * <p>카탈로그 읽기 하나(잠금 없음)라 싸다. 차이를 빼지 않고 <b>합집합</b>으로 두는 이유: 잠금을 쥔 다른 동기화(옛 등급 기준 실행 준비 등)가 아직 커밋하지
   * 않은 GRANT 는 이 읽기에 보이지 않는다 — 차이 슬롯은 그 GRANT 를 받을 수 있는 슬롯이라 계속 막는다(fail-closed). 읽기가 실패하면 실제 ACL 을
   * 모르므로 새 등급에서 못 읽는 모든 슬롯을 막는다.
   */
  private Set<Integer> exposedWithCurrentHolders(
      DSLContext ds,
      long tenantId,
      String table,
      Set<Integer> exposed,
      List<LevelPolicy> levels,
      long toLevelId) {
    Set<Integer> block = new TreeSet<>(exposed);
    try {
      String[] roles = TenantPipelineRole.pythonReadRoleNames(tenantId).toArray(new String[0]);
      Map<String, Set<String>> holders = new HashMap<>();
      for (var r : fetchSlotSelectGrants(ds, DataSchema.current(), List.of(table), roles)) {
        holders.computeIfAbsent(table, x -> new TreeSet<>()).add(r.get(1, String.class));
      }
      Set<Integer> current =
          PythonReadGrantBackend.slotsOf(
              holders, k -> TenantPipelineRole.pythonReadRoleName(tenantId, k));
      current.removeAll(PythonReadSlots.grantSlotsFor(levels, toLevelId));
      block.addAll(current);
    } catch (RuntimeException e) {
      log.warn(
          "데이터셋 등급 좁히기: 현재 슬롯 권한을 읽지 못해 새 등급에서 못 읽는 모든 슬롯을 막는다(tenant={} table={})",
          tenantId,
          table,
          e);
      block.addAll(PythonReadSlots.slotsLosingAccess(levels, null, toLevelId));
    }
    return block;
  }

  /**
   * 분리 설치의 커밋 직전 좁히기(WD-701 3차, 설계 결정 Q1 A) — 회수를 데이터 저장소에서 <b>플랫폼 커밋보다 먼저</b> 커밋되게 한다. 연결은 둘 중
   * 하나다.
   *
   * <ul>
   *   <li><b>이 트랜잭션에 데이터 저장소 연결이 이미 묶여 있으면</b>(같은 트랜잭션에서 테이블 생성·맞바꿈 등 데이터 저장소를 이미 썼으면) 그 연결에서, 이름
   *       있는 SAVEPOINT 안에서 회수한다({@link #narrowOnBoundDataStoreConnection}). 묶인 연결의 커밋 직전 콜백(기본 순서 =
   *       가장 늦음)은 이 리스너(가장 높은 우선순위) 뒤, 플랫폼 커밋 앞에 데이터 저장소를 커밋한다 — 회수도 그때 함께 커밋된다. 같은 세션이라 그 트랜잭션이 쥔
   *       테넌트 잠금(재진입)·테이블 잠금에 막히지 않고, 아직 커밋 안 된 새 테이블도 보인다. 이 트랜잭션은 이미 그 데이터 저장소 커밋에 기대고 있었으므로 새
   *       결합도 아니다.
   *   <li><b>아니면</b> 데이터 저장소 풀에서 연결을 하나 따로 빌려 회수하고 곧바로 커밋한다({@link
   *       #narrowOnOwnDataStoreConnection}). 이 트랜잭션에 새로 묶으면 안 된다 — 커밋 직전 단계에서 처음 묶은 연결은 플랫폼 커밋
   *       <b>뒤</b>에야 커밋된다({@link DataStoreConnectionBinding#currentlyBoundConnectionOrNull}).
   * </ul>
   *
   * <p><b>시간 예산.</b> {@link PreCommitDeadline}(운영 {@link PreCommitDeadline#DEFAULT_BOUNDS}) — 잠금
   * 대기는 남은 예산 안에서만, 모든 문장에 queryTimeout, 문장마다 예산 확인. 예산을 넘기거나(문장 취소 57014 포함) 접속·커밋하지 못하면 어디까지
   * 회수했는지 모르므로 "새 등급에서 못 읽는 모든 슬롯" 을 막도록 "확인 못 함" 을 돌려준다(Tibero 판과 같은 규칙). 그 밖의 예외는 그대로 던진다 — 파사드가
   * 모든 슬롯을 막는다.
   *
   * <p><b>순서 위험(받아들임).</b> 데이터 저장소 회수가 커밋된 뒤 플랫폼 트랜잭션이 롤백되면(뒤따르는 커밋 직전 작업·플랫폼 커밋 실패) 슬롯은 잠시 원래보다
   * <b>적게</b> 읽는다(fail-closed). 리스너가 롤백 완료 때 그 데이터셋 재동기화를 실행기에 넣어 되살린다({@link
   * PythonReadGrantListener#narrowDatasetBeforeCommit}). 실행 준비(JIT)·일 1회 동기화도 회복한다.
   *
   * <p><b>잠금이 플랫폼 커밋보다 먼저 풀리는 틈(WD-701 3차 B1).</b> 테넌트 동기화 잠금은 데이터 저장소 트랜잭션에 묶여 회수와 함께 커밋 — 곧 풀린다.
   * 플랫폼 커밋 전이라 그 틈에 잠금을 기다리던 동기화(실행 준비·전체·실행기)는 아직 커밋 안 된 <b>옛 등급</b>을 읽고 방금 회수한 슬롯에 다시 GRANT 할 수
   * 있다. 그래서 확인된 결과를 "따로 먼저 커밋됨"({@link NarrowResult#separately})으로 표시하고, 리스너가 커밋 뒤 회수 필수 뒷정리({@link
   * #syncDatasetAfterSeparateNarrowing})로 새 등급 기준으로 다시 회수하고 그 슬롯 세션을 끊는다. 같은 DB 설치는 잠금이 발행 트랜잭션과 함께
   * 풀려 틈이 없다. Tibero 는 REVOKE(DDL)가 곧바로 반영되지만 테넌트 잠금은 플랫폼 잠금({@code PlatformLocks})이라 플랫폼 커밋 때 풀린다
   * — 기다리던 동기화는 커밋된 새 등급을 읽으므로 이 틈이 없다.
   */
  private NarrowResult narrowOnSeparateDataStore(
      long tenantId,
      long datasetId,
      String table,
      Set<Integer> exposed,
      List<LevelPolicy> levels,
      long toLevelId) {
    PreCommitDeadline deadline = new PreCommitDeadline(preCommitBounds);
    Connection bound = boundDataStoreConnectionOrNull();
    try {
      NarrowResult result =
          bound != null
              ? narrowOnBoundDataStoreConnection(
                  bound, deadline, tenantId, datasetId, table, exposed, levels, toLevelId)
              : narrowOnOwnDataStoreConnection(
                  deadline, tenantId, datasetId, table, exposed, levels, toLevelId);
      // 확인됐으면 회수·잠금이 플랫폼보다 먼저 커밋된다 — 커밋 뒤 회수 필수 뒷정리가 필요하다(B1, 위 Javadoc).
      return result.separately();
    } catch (RuntimeException e) {
      if (!deadline.exceededBy(e)) {
        throw e; // 시간·접속과 무관한 실패 — 파사드가 savepoint 를 되돌리고 모든 슬롯을 막는다
      }
      Set<Integer> wider =
          new TreeSet<>(PythonReadSlots.slotsLosingAccess(levels, null, toLevelId));
      wider.addAll(exposed);
      log.warn(
          "데이터셋 등급 좁히기(분리 설치): 커밋 직전 시간 상한({}) 초과 또는 데이터 저장소 접속·커밋 실패로 대체 경로로 막는다(tenant={} dataset={} slots={}): {}",
          deadline.bounds(),
          tenantId,
          datasetId,
          wider,
          e.toString());
      return NarrowResult.unconfirmed(wider);
    }
  }

  /**
   * 이 트랜잭션에 이미 묶인 데이터 저장소 연결(없으면 null) — 절대 새로 묶지 않는다({@link #narrowOnSeparateDataStore}).
   *
   * <p><b>전제.</b> 묶인 연결은 커밋 직전 단계가 시작되기 <b>전에</b> 묶였어야 한다 — 그래야 그 연결의 커밋 직전 콜백이 시작 때 찍은 콜백 스냅숏에 들어
   * 플랫폼 커밋 전에 데이터 저장소를 커밋한다. 이 리스너보다 먼저(같거나 높은 우선순위) 도는 다른 커밋 직전 작업이 처음 묶은 연결이면 그 커밋은 플랫폼 커밋 뒤로
   * 밀린다. 지금은 그런 작업이 없다(이 리스너가 가장 높은 우선순위).
   */
  private Connection boundDataStoreConnectionOrNull() {
    return dsl.configuration().connectionProvider() instanceof DataStoreConnectionBinding binding
        ? binding.currentlyBoundConnectionOrNull()
        : null;
  }

  /**
   * 이미 묶인 데이터 저장소 연결에서 이름 있는 SAVEPOINT 안에서 회수한다 — 커밋은 묶인 연결의 커밋 직전 콜백이 플랫폼 커밋 전에 한다. 확인되면 SAVEPOINT
   * 를 풀고(회수 유지), 확인 못 했거나 예외면 SAVEPOINT 로 되돌린다: 묶인 연결의 문장 오류(예산 초과로 취소된 문장 포함)는 데이터 저장소 트랜잭션 전체를
   * aborted 로 남겨 그 커밋 — 곧 게시자 커밋 — 을 실패시키기 때문이다(같은 DB 설치의 파사드 savepoint 와 같은 이유). SAVEPOINT 문장들은
   * 예산과 무관하게(넘긴 뒤에도 되돌려야 하므로) 원래 연결로, 문장 상한만 걸고 보낸다({@link #savepointStatement}).
   */
  private NarrowResult narrowOnBoundDataStoreConnection(
      Connection bound,
      PreCommitDeadline deadline,
      long tenantId,
      long datasetId,
      String table,
      Set<Integer> exposed,
      List<LevelPolicy> levels,
      long toLevelId) {
    savepointStatement(bound, deadline, "SAVEPOINT " + NARROW_SAVEPOINT);
    NarrowResult result;
    try {
      result =
          narrowWith(
              DSL.using(deadline.bind(bound), SQLDialect.POSTGRES),
              tenantId,
              datasetId,
              table,
              exposed,
              levels,
              toLevelId,
              deadline.cap(NARROW_LOCK_WAIT));
    } catch (RuntimeException e) {
      try {
        savepointStatement(bound, deadline, "ROLLBACK TO SAVEPOINT " + NARROW_SAVEPOINT);
      } catch (RuntimeException rollbackFailure) {
        e.addSuppressed(rollbackFailure); // 원래 원인을 가리지 않는다
      }
      throw e;
    }
    savepointStatement(
        bound,
        deadline,
        (result.confirmed() ? "RELEASE SAVEPOINT " : "ROLLBACK TO SAVEPOINT ") + NARROW_SAVEPOINT);
    return result;
  }

  /** 묶인 데이터 저장소 연결에서 커밋 직전 좁히기를 감싸는 SAVEPOINT 이름 — applyInTx 의 문장 savepoint 와 겹치지 않게 따로 둔다. */
  private static final String NARROW_SAVEPOINT = "python_read_narrow";

  /**
   * SAVEPOINT·RELEASE·ROLLBACK TO SAVEPOINT 를 원래 연결로 보낸다 — 남은 예산이 아니라 문장 상한({@link
   * PreCommitDeadline#statementTimeoutSeconds})만 건다. 예산을 넘긴 뒤에도 되돌리기는 꼭 해야 하고, 그래도 느린 데이터 저장소에서 무한정
   * 멈추지는 않게 한다.
   */
  private static void savepointStatement(Connection conn, PreCommitDeadline deadline, String sql) {
    try (Statement st = conn.createStatement()) {
      st.setQueryTimeout(deadline.statementTimeoutSeconds());
      st.execute(sql);
    } catch (SQLException e) {
      throw new DataAccessResourceFailureException("데이터 저장소 SAVEPOINT 문장 실패: " + sql, e);
    }
  }

  /**
   * 데이터 저장소 풀에서 연결을 따로 빌려 회수하고 곧바로 커밋한다 — 반환 시점에 회수가 데이터 저장소에 커밋돼 있다(플랫폼 커밋 전). 확인 못 함(회수 일부 실패·잠금
   * 대기 초과)이어도 이미 성공한 회수는 커밋한다 — 회수는 좁히기라 늘 안전하고, 남은 슬롯은 대체 경로가 막는다. 예외면 되돌린다.
   *
   * <p><b>접속 상한.</b> 빌리기는 {@link #borrowDataStoreConnection} 이 접속 상한({@link
   * PreCommitDeadline#connectTimeout})까지만 기다린다.
   *
   * <p><b>네트워크 상한.</b> 빌린 직후 연결에 남은 예산(최소 1초)만큼 네트워크 상한을 건다({@link #applyNetworkTimeout}). 문장 상한은
   * 서버에 취소를 보내는 방식이라, 네트워크가 소리 없이 패킷을 버리면 commit·rollback·취소가 응답을 영원히 기다린다(풀에 socketTimeout 설정 없음).
   * Hikari 는 반납 때 바뀐 네트워크 상한을 원래대로 되돌린다.
   */
  private NarrowResult narrowOnOwnDataStoreConnection(
      PreCommitDeadline deadline,
      long tenantId,
      long datasetId,
      String table,
      Set<Integer> exposed,
      List<LevelPolicy> levels,
      long toLevelId) {
    try (Connection conn = borrowDataStoreConnection(deadline)) {
      applyNetworkTimeout(conn, deadline.networkTimeoutMillis());
      conn.setAutoCommit(false);
      boolean committed = false;
      try {
        NarrowResult result =
            narrowWith(
                DSL.using(deadline.bind(conn), SQLDialect.POSTGRES),
                tenantId,
                datasetId,
                table,
                exposed,
                levels,
                toLevelId,
                deadline.cap(NARROW_LOCK_WAIT));
        conn.commit();
        committed = true;
        return result;
      } finally {
        // autoCommit 은 되돌리지 않는다 — Hikari 가 반납 때 바뀐 연결 상태를 되돌린다(여기서 되돌리다 실패하면 원래 예외를 가린다).
        if (!committed) {
          rollbackQuietly(conn);
        }
      }
    } catch (SQLException e) {
      // 접속·커밋 실패 — 회수가 커밋됐는지 모른다. 예산 초과와 같이 다룬다(새 등급에서 못 읽는 모든 슬롯을 막는다).
      throw new DataAccessResourceFailureException("데이터 저장소 커밋 직전 좁히기 연결·커밋 실패", e);
    }
  }

  /**
   * 커밋 직전 좁히기용 데이터 저장소 연결을 접속 상한({@link PreCommitDeadline#connectTimeout}) 안에서 빌린다(WD-701 3차 리뷰).
   *
   * <p><b>왜 따로 스레드에서 빌리는가.</b> Hikari 는 호출마다 대기 상한을 줄 수 없다(풀 설정 {@code connection-timeout}, 기본
   * 30초). 데이터 저장소가 죽었거나 풀이 말랐으면 빌리기가 그만큼 멈추고, 그동안 이 요청은 플랫폼 커넥션을 쥔다 — 등급 변경마다 30초. 그래서 (1) 풀이 정말로
   * 포화(빌려 간 연결 = 최대 크기 + 기다리는 스레드 있음)면 빌리지 않고 곧바로 예산 초과로 보고({@link #dataStorePoolBusy}), (2) 빌리기는
   * 작은 전용 스레드 풀에서 하며 접속 상한만 기다린다. 넘기면 예산 초과로 대체 경로에 맡기고, 늦게 도착한 연결은 받는 즉시 닫아 풀에 돌려준다. 전용 스레드가 모두
   * 바쁘면(데이터 저장소가 느린 동안 몰린 요청) 거절 = 예산 초과다.
   */
  private Connection borrowDataStoreConnection(PreCommitDeadline deadline) {
    if (dataStorePoolBusy()) {
      throw new PreCommitDeadline.BudgetExceeded("데이터 저장소 풀 포화");
    }
    deadline.check("데이터 저장소 접속");
    return borrowWithin(deadline.connectTimeout());
  }

  /**
   * 데이터 저장소 연결을 빌리기 전용 스레드에서 빌리고 최대 {@code timeout} 만 기다린다 — 커밋 직전 좁히기({@link
   * #borrowDataStoreConnection})와 커밋 콜백의 미확인 표식 쓰기({@link PythonReadUnconfirmedMarks#persist},
   * WD-701 4차)가 함께 쓴다. 넘기면 {@link PreCommitDeadline.BudgetExceeded}, 늦게 온 연결은 받는 즉시 돌려준다. 빌리기 스레드가
   * 모두 바쁘면 거절이다.
   */
  Connection borrowWithin(Duration timeout) {
    return borrowWithin(timeout, connectionBorrower);
  }

  /** {@link #borrowWithin(Duration)} 와 같되 빌리기 스레드 풀을 고른다(표식 쓰기는 따로 — 리뷰 5). */
  private Connection borrowWithin(Duration timeout, ThreadPoolExecutor borrowerPool) {
    CompletableFuture<Connection> borrowed;
    try {
      borrowed =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return dataStoreDataSource.getConnection();
                } catch (SQLException e) {
                  throw new CompletionException(e);
                }
              },
              borrowerPool);
    } catch (RejectedExecutionException e) {
      throw new PreCommitDeadline.BudgetExceeded("데이터 저장소 접속 스레드 포화");
    }
    try {
      return borrowed.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      borrowed.thenAccept(PythonReadGrantSync::closeQuietly); // 늦게 온 연결은 받는 즉시 돌려준다
      throw new PreCommitDeadline.BudgetExceeded("데이터 저장소 접속 상한");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      borrowed.thenAccept(PythonReadGrantSync::closeQuietly);
      throw new PreCommitDeadline.BudgetExceeded("데이터 저장소 접속 대기 중 인터럽트");
    } catch (ExecutionException e) {
      throw new DataAccessResourceFailureException("데이터 저장소 연결 획득 실패", e.getCause());
    }
  }

  /**
   * 커밋 콜백의 미확인 표식 쓰기용 연결 빌리기(WD-701 4차) — 지금 곧바로 내줄 연결이 없으면(쉬는 연결 0 + 최대 크기) <b>기다리지 않고</b> 곧바로
   * 실패한다. 커밋 콜백은 바깥 플랫폼 커넥션을 쥔 채 돈다 — 분리 아닌 설치에서는 데이터 저장소 풀이 곧 플랫폼 풀이라, 동시 커밋들이 풀을 다 쥔 채 서로의 반납을
   * 기다리면(WD-594 의 이중 점유) 모두 상한만큼 멈춘다. 실패하면 부르는 쪽이 메모리 표식 + 실행기 재시도(커넥션을 쥐지 않은 스레드)로 넘긴다.
   */
  private Connection borrowForMark(Duration timeout) {
    if (dataStorePoolHasNoFreeConnection()) {
      throw new PreCommitDeadline.BudgetExceeded("데이터 저장소 풀에 쉬는 연결 없음(표식 쓰기)");
    }
    return borrowWithin(timeout, markBorrower);
  }

  /** 표식 쓰기 연결 빌리기 전용 스레드 수 — 커밋 직전 좁히기의 빌리기 스레드와 나눠, 표식 쓰기가 몰려도 좁히기가 대체 경로로 밀리지 않게 한다(리뷰 5). */
  static final int MARK_BORROW_THREADS = 2;

  /** 표식 쓰기 연결 빌리기 전용 스레드 풀(데몬, 큐 없음 — 모자라면 거절 = 메모리 표식 + 재시도). */
  private final ThreadPoolExecutor markBorrower =
      new ThreadPoolExecutor(
          0,
          MARK_BORROW_THREADS,
          30L,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          r -> {
            Thread th = new Thread(r, "python-read-mark-borrow");
            th.setDaemon(true);
            return th;
          });

  /** 데이터 저장소 풀에 곧바로 내줄 연결이 없는가 — 쉬는 연결 0 이고 최대 크기까지 찼다. Hikari 가 아니거나 알 수 없으면 false. */
  private boolean dataStorePoolHasNoFreeConnection() {
    try {
      if (!dataStoreDataSource.isWrapperFor(HikariDataSource.class)) {
        return false;
      }
      HikariDataSource hikari = dataStoreDataSource.unwrap(HikariDataSource.class);
      HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
      return pool != null
          && pool.getIdleConnections() == 0
          && pool.getTotalConnections() >= hikari.getMaximumPoolSize();
    } catch (SQLException e) {
      return false;
    }
  }

  /** 커밋 직전 좁히기의 연결 빌리기 전용 스레드 수 — 데이터 저장소가 느릴 때 이만큼만 동시에 기다리고, 넘치면 대체 경로로 간다. */
  static final int CONNECTION_BORROW_THREADS = 4;

  /** 연결 빌리기 전용 스레드 풀(데몬, 쉬면 줄어든다) — {@link #borrowDataStoreConnection}. 대기 큐 없이 스레드가 모자라면 거절한다. */
  private final ThreadPoolExecutor connectionBorrower =
      new ThreadPoolExecutor(
          0,
          CONNECTION_BORROW_THREADS,
          30L,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          r -> {
            Thread th = new Thread(r, "python-read-narrow-borrow");
            th.setDaemon(true);
            return th;
          });

  /** 종료 — 빌리기 스레드를 멈춘다(데몬이라 종료를 막지는 않는다). */
  @PreDestroy
  void shutdownConnectionBorrower() {
    connectionBorrower.shutdownNow();
    markBorrower.shutdownNow();
  }

  /**
   * 데이터 저장소 풀이 <b>정말로</b> 포화인가(WD-701 4차 리뷰 ② — 판정을 좁혔다). Hikari 가 아니거나 알 수 없으면 false(빌리기를 시도한다 —
   * 접속 상한이 여전히 묶는다).
   */
  private boolean dataStorePoolBusy() {
    try {
      if (!dataStoreDataSource.isWrapperFor(HikariDataSource.class)) {
        return false;
      }
      HikariDataSource hikari = dataStoreDataSource.unwrap(HikariDataSource.class);
      HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
      return pool != null
          && poolSaturated(
              pool.getActiveConnections(),
              hikari.getMaximumPoolSize(),
              pool.getThreadsAwaitingConnection());
    } catch (SQLException e) {
      return false;
    }
  }

  /**
   * 포화 판정 — <b>빌려 간 연결이 최대 크기에 닿았고 그래도 기다리는 스레드가 있을 때만</b> 포화다(WD-701 4차 리뷰 ②).
   *
   * <p><b>왜 좁혔는가(내가 정한 것).</b> 예전 판정은 "기다리는 스레드가 하나라도 있거나, 쉬는 연결 0 + 최대 크기" 였다. 그런데 기다리는 스레드 하나는 풀이
   * 새 연결을 만드는 몇 ms 동안(최대 크기 전)에도 흔히 보이고, 쉬는 연결 0 + 최대 크기도 곧 반납될 연결을 기다리면 되는 평범한 바쁨이다. 포화로 보면 곧바로 대체
   * 경로 — 그 데이터셋을 더는 읽으면 안 되는 슬롯 세션을 테넌트 전체에서 끊기 — 로 가서 WD-664 5 의 가용성 회귀가 돌아온다. 그래서 정말로 말라 줄이 선 경우만
   * 빌리기를 건너뛰고, 나머지는 빌리기에 맡긴다. 빌리기는 이미 묶여 있다: 접속 상한({@link PreCommitDeadline#connectTimeout}, 운영
   * 3초)만 기다리고, 빌리기 전용 스레드({@link #CONNECTION_BORROW_THREADS}개)가 모두 바쁘면 곧바로 거절이다. 그래서 판정을 좁혀도 커밋 지연의
   * 최악은 접속 상한으로 묶인다.
   */
  static boolean poolSaturated(int active, int maximumPoolSize, int threadsAwaiting) {
    return active >= maximumPoolSize && threadsAwaiting > 0;
  }

  /**
   * 연결에 네트워크 상한(밀리초)을 건다 — 실패해도(드라이버 미지원 등) 경로를 깨지 않고 WARN 만 남긴다(문장 상한·예산은 여전히 묶는다). 실행기는 호출 스레드에서
   * 곧바로 도는 직접 실행기다: PG 드라이버는 이 실행기를 쓰지 않고 소켓 읽기 상한만 바꾸며, 상한 초과 때 연결을 끊는 작업을 하는 드라이버도 빌리기 전용 스레드
   * 풀(포화면 거절)에 기대지 않게 한다.
   */
  static void applyNetworkTimeout(Connection conn, int millis) {
    try {
      conn.setNetworkTimeout(Runnable::run, millis);
    } catch (SQLException | RuntimeException e) {
      log.warn("데이터 저장소 연결 네트워크 상한 설정 실패(계속 진행): {}", e.toString());
    }
  }

  /** 연결을 조용히 닫는다(늦게 도착한 빌린 연결 반납용). */
  private static void closeQuietly(Connection conn) {
    try {
      conn.close();
    } catch (SQLException e) {
      log.debug("데이터 저장소 연결 닫기 실패(무시): {}", e.getClass().getName());
    }
  }

  /** 되돌리기 — 실패는 로그만(연결은 곧 닫혀 풀이 정리한다). */
  private static void rollbackQuietly(Connection conn) {
    try {
      conn.rollback();
    } catch (SQLException e) {
      log.warn("데이터 저장소 커밋 직전 좁히기 되돌리기 실패: {}", e.getClass().getName());
    }
  }

  /**
   * 쓰기 경로 훅 — 트랜잭션 안이면 커밋 뒤 {@link PythonReadSyncExecutor} 에서 비동기로, 아니면 즉시 syncTable 하고 실패는 로그만
   * 남긴다(스펙 §4.3). 공통부는 {@link PythonReadGrantBackend#scheduleTableSync}.
   */
  @Override
  public void syncTableAfterCommit(String tableName) {
    PythonReadGrantBackend.scheduleTableSync(
        tableName, log, "", afterCommitExecutor, this::syncTable, this::syncTablesWith);
  }

  /**
   * 그 테넌트의 동기화 잠금(트랜잭션 범위 advisory)을 주어진 연결의 현재 트랜잭션에서 잡는다 — 트랜잭션이 끝나면 저절로 풀린다.
   *
   * <p>동기화 자신과, 동기화의 GRANT 와 겹치면 안 되는 DDL 이 함께 쓴다. GRANT 는 테이블 잠금 없이 pg_class 행(ACL)을 고치므로 같은 테이블의
   * {@code DROP TABLE} 과 겹치면 한쪽이 {@code tuple concurrently updated/deleted}(XX000)로 깨진다. REPLACE
   * 맞바꿈(원본 DROP)이 바로 그 경우다 — 같은 출력에 겹친 실행이 맞바꾼 직후 거는 이 동기화의 GRANT 와 다른 실행의 맞바꿈이 부딪혀 실행이 실패했다(WD-151
   * 라이브 확인).
   *
   * <p>이 잠금은 <b>데이터 저장소</b> 연결에서 잡는다 — 동기화 GRANT 와 맞바꿈 DROP 이 모두 데이터 저장소에서 일어나므로 직렬화도 그 저장소 안에서 끝나야
   * 한다(한 직렬화 = 한 저장소, WD-493). 테넌트마다 백엔드는 하나라 Tibero 판({@code TiberoPythonReadGrantSync})과 겹치지 않는다.
   */
  public static void lockTenantSync(DSLContext ctx, long tenantId) {
    // SQL·키 식은 PostgresPlatformLocks 한곳에 둔다(WD-644). 잡는 곳은 데이터 저장소 DSL 이다.
    // Tibero 판(플랫폼 PlatformLocks)과는 저장소가 다르지만 테넌트마다 백엔드는 하나라 겹치지 않는다(WD-493).
    PostgresPlatformLocks.xactLock(ctx, syncLockKey(tenantId));
  }

  /**
   * 테넌트 동기화 잠금 원시 동작(WD-644) — PG 데이터 경로라 <b>데이터 저장소 DSL</b> 로 잡는다(동기화가 GRANT 를 거는 그 DB 의 트랜잭션).
   * SQL 은 {@link PostgresPlatformLocks} 의 정적 도우미를 그대로 쓰고, 대기·시도·묶인 대기 선택은 {@link
   * PythonReadGrantBackend#withTenantSyncLock} 한곳에서 한다.
   */
  private final PythonReadGrantBackend.SyncLockOps lockOps =
      new PythonReadGrantBackend.SyncLockOps() {
        @Override
        public void lock(String key) {
          PostgresPlatformLocks.xactLock(dsl, key);
        }

        @Override
        public boolean tryLock(String key) {
          return PostgresPlatformLocks.tryXactLock(dsl, key);
        }

        @Override
        public void lock(String key, Duration timeout) {
          PostgresPlatformLocks.xactLock(dsl, key, timeout);
        }
      };

  /**
   * 테넌트 동기화 잠금의 키 문자열. PG 판({@link #lockTenantSync})과 Tibero 판({@code TiberoPythonReadGrantSync})이
   * 키 문자열을 손으로 쓰지 않고 이 한 곳을 공유한다(키 일관성). 두 판은 서로 다른 저장소에서 잡으므로 서로를 막지는 않는다(WD-493) — 테넌트마다 백엔드는
   * 하나다.
   */
  public static String syncLockKey(long tenantId) {
    return "python_read_sync_t" + tenantId;
  }

  /**
   * {@link #lockTenantSync} 의 시도형 — 같은 키로 최대 {@code maxWait} 동안 시도한다. 구현은 플랫폼 잠금의 시도형과 하나다({@link
   * PostgresPlatformLocks#tryXactLock(DSLContext, String, Duration)}) — 여기서는 연결(분리 설치면 데이터 저장소)만
   * 고른다.
   */
  static boolean tryLockTenantSync(DSLContext ctx, long tenantId, Duration maxWait) {
    return PostgresPlatformLocks.tryXactLock(ctx, syncLockKey(tenantId), maxWait);
  }

  /**
   * PYTHON 실행 직전 준비(JIT) — 잠금 안에서 읽은 등급 스냅샷으로 실행 주체의 슬롯 k 를 계산하고, <b>그 슬롯 롤 하나의</b> ACL 만 계산값에 맞춘다
   * (CR3 — 매 스텝 10개 롤 전부를 맞추지 않는다. 다른 슬롯은 이 스크립트가 접속하지 않는 롤이라 이벤트·일 1회·그 슬롯의 실행이 맞춘다). 실행 슬롯의 과권한은
   * 매번 실제 ACL 로 확인하므로 손으로 건 GRANT 도 회수하거나(실패하면) 거부한다 — "마지막 동기화 이후 변경 없음" 단락은 이 확인을 건너뛰게 되므로 쓰지
   * 않는다. 어떤 실패든 {@link PythonReadAccessException}(fail-closed). 테넌트 실행 롤(pipeline_executor_t*)로
   * 대체하는 경로는 없다 — 그 롤은 모든 등급을 읽으므로 대체하는 순간 등급 경계가 사라진다.
   *
   * <p>커밋 직후 세션 끊기를 확인하지 못한 슬롯(미확인 표식, WD-624 — 4차부터 데이터 저장소 표라 모든 인스턴스가 본다)이 실행 슬롯이면 슬롯을 맞추기
   * <b>전에</b> 표식이 요구하는 세션(표식 전에 시작한 세션, 등급 정의 변경이면 전부)을 먼저 끊는다 — 옛 등급으로 슬롯을 받은 세션이 살아 있는 채로 이 실행이
   * 같은 슬롯을 넓히지 않게. 끊기는 잠금·커넥션을 쥐기 전에 기다린다(상한 {@link #TERMINATE_WAIT}). 끊지 못하면 실행을 거부한다.
   *
   * @return 실행에 쓸 슬롯 번호(1~10)
   */
  public int prepareForRun(Clearance runAs) {
    return prepareForRun(runAs, runAs.rank());
  }

  /**
   * {@link #prepareForRun(Clearance)} 와 같되, 슬롯을 실행 주체 자격 rank 가 아니라 {@code readRank}(스텝이 다루는 데이터의
   * 최고 등급 — PipelineSecurityGate.pythonReadRank)로 고른다. readRank 는 자격 rank 이하여야 한다 — 넘으면 자격 밖 등급을 읽게
   * 되므로 거부한다 (fail-closed).
   */
  @Override
  public int prepareForRun(Clearance runAs, int readRank) {
    // 사전 검사·슬롯 판정·회수 실패 거부는 백엔드 공통 규칙(PythonReadRunGate)이다 — Tibero 판과 갈라지지 않게 한 곳에 둔다.
    return PythonReadRunGate.prepare(
        runAs,
        readRank,
        () -> {
          // 미확인 표식(WD-624 → WD-701 4차: 데이터 저장소 표 + 메모리 대비)을 읽는다 — 읽기 실패는 예외 → 게이트가 실행을
          // 거부(fail-closed).
          // 표식이 없으면 잠금을 기다려 맞춘다(WD-644 의 대기 경로 — 실행 준비는 호출 스레드에서 돌아 실행기 줄을 막지 않는다).
          long tenantId = runAs.tenantId();
          Map<Integer, PythonReadUnconfirmedMarks.SlotMarks> marks =
              unconfirmedMarks.read(dsl, tenantId);
          Map<Integer, OffsetDateTime> firstKill = new TreeMap<>();
          if (!marks.isEmpty()) {
            // 첫 끊기는 이 실행의 슬롯 표식만(WD-664 8), 표식이 요구하는 세션만(WD-701 4차). 실행 슬롯은 잠금 밖 추정값이다:
            // security_level 이
            // RLS 테이블이라 트랜잭션(GUC) 안에서 읽어야 한다(밖에서 읽으면 0행 → 끊기를 건너뛰는 fail-open). 추정이 잠금 뒤 등급과 달라져도
            // 잠금 안 재확인이 잠금 뒤 실행 슬롯의 표식을 다시 읽고 끊는다.
            Integer estimated =
                requiresNew.execute(
                    status -> PythonReadSlots.slotFor(levelRepository.findAll(), readRank));
            if (estimated != null && marks.containsKey(estimated)) {
              firstKill.put(estimated, marks.get(estimated).cutoff());
            }
          }
          PythonReadUnconfirmedMarks.SlotMarks firstMarks =
              firstKill.isEmpty() ? null : marks.get(firstKill.keySet().iterator().next());
          // 표식이 없어도 잠금 안에서 표식을 다시 읽는다(리뷰 4) — 잠금 밖 읽기와 잠금 사이에 커밋된 등급 정의 변경의 표식을 놓치지 않게. 잠금 밖
          // 읽기는 첫 끊기 범위를 정하는 데만 쓴다.
          return prepareUnderMarks(tenantId, readRank, firstKill, firstMarks);
        },
        slot -> TenantPipelineRole.pythonReadRoleName(runAs.tenantId(), slot));
  }

  /**
   * 트랜잭션(=테넌트 GUC) 안에서, 호출자가 그 트랜잭션에 테넌트 동기화 잠금을 <b>잡은 뒤</b> 호출된다({@link #lockedSync}·{@link
   * #syncTablesWith}). 잠금을 어떻게 잡을지(대기·시도·묶인 대기)는 호출자 몫이라 이 메서드는 언제나 끝까지 동기화한다(WD-644). 세션 끊기는 여기서
   * 하지 않는다(잠금을 쥔 채 소유자 풀을 기다리지 않게, WD-624).
   *
   * @param onlyTable null 이면 스키마 전체, 아니면 그 테이블만
   * @param runRank null 이면 슬롯 롤 전부, 아니면 그 자격 rank 의 실행 슬롯 롤 하나만(실행 준비) — 슬롯은 잠금 뒤 읽은 등급 스냅샷으로 계산한다
   * @param noWidenSlots 넓히지 않을(회수만 할) 슬롯 — 미확인 표식이 남은 슬롯({@link #markedSlotsUnderLock})
   */
  private SyncResult syncInTx(String onlyTable, Integer runRank, Set<Integer> noWidenSlots) {
    long tenantId = TenantContext.require("PYTHON 읽기 권한 동기화");
    return applyInTx(dsl, tenantId, onlyTable, runRank, false, noWidenSlots);
  }

  /**
   * 잠금을 잡은 뒤의 본체 — 계산값과 실제 ACL 의 차이를 적용한다. 동기화({@link #syncInTx})와 커밋 직전 좁히기({@link
   * #narrowDatasetInCurrentTx})가 함께 쓴다.
   *
   * @param revokeOnly true 면 남는 권한 회수만 하고 부족한 GRANT 는 걸지 않는다(커밋 직전 좁히기 — 넓히기는 커밋 뒤 실행기 몫)
   */
  private SyncResult applyInTx(
      DSLContext ds, long tenantId, String onlyTable, Integer runRank, boolean revokeOnly) {
    return applyInTx(ds, tenantId, onlyTable, runRank, revokeOnly, Set.of());
  }

  /**
   * {@link #applyInTx(DSLContext, long, String, Integer, boolean)} 에 "넓히지 않을 슬롯" 을 더한 것(재검토 1) — 그
   * 슬롯 롤에는 부족한 GRANT 를 걸지 않고 남는 권한 회수만 한다.
   */
  private SyncResult applyInTx(
      DSLContext ds,
      long tenantId,
      String onlyTable,
      Integer runRank,
      boolean revokeOnly,
      Set<Integer> noWidenSlots) {
    Set<String> noWidenRoles = new TreeSet<>();
    for (int slot : noWidenSlots) {
      noWidenRoles.add(TenantPipelineRole.pythonReadRoleName(tenantId, slot));
    }
    AclPlan plan = planAcl(ds, tenantId, onlyTable, runRank);
    if (plan.existingRoles().isEmpty()) {
      if (runRank == null) {
        log.warn("PYTHON 읽기 슬롯 롤이 없다 — 동기화 생략(tenant={})", tenantId);
      }
      return new SyncResult(plan.levelsAsc(), plan.availableSlots(), 0, Map.of(), plan.runSlot());
    }
    String schema = plan.schema();
    List<LevelPolicy> levelsAsc = plan.levelsAsc();
    Set<Integer> availableSlots = plan.availableSlots();
    int runSlot = plan.runSlot();
    String[] roleArray = plan.existingRoles().toArray(new String[0]);
    Map<String, Long> oids = plan.oids();
    List<String> physical = plan.physical();
    Function<String, Set<String>> wantOf = plan.wantOf();
    Map<String, Set<String>> actual = plan.actual();
    return applyPlan(
        ds,
        tenantId,
        revokeOnly,
        noWidenRoles,
        schema,
        levelsAsc,
        availableSlots,
        runSlot,
        roleArray,
        oids,
        physical,
        wantOf,
        actual);
  }

  /**
   * 계산값과 실제 ACL 묶음(WD-701 4차) — 적용({@link #applyInTx})과 등급 정의 변경 동기화의 "넓어질 슬롯" 계산({@link
   * #widenedSlots})이 <b>같은 규칙</b>을 쓰게 한 곳({@link #planAcl})에서 만든다. 둘이 따로 계산하면 어긋나 넓어지는 슬롯을 놓칠 수 있다.
   *
   * @param existingRoles 다룰 슬롯 롤 중 실제로 있는 롤(비면 나머지 필드는 비어 있다)
   * @param physical 스키마의 관계 이름(오름차순)
   * @param wantOf 관계 → 받아야 할 슬롯 롤(일반·분할 테이블만, 그 밖은 없음)
   * @param actual 관계 → 지금 SELECT 를 가진 슬롯 롤
   */
  private record AclPlan(
      String schema,
      List<LevelPolicy> levelsAsc,
      List<String> slotRoleNames,
      Set<String> existingRoles,
      Set<Integer> availableSlots,
      int runSlot,
      Map<String, Long> oids,
      List<String> physical,
      Function<String, Set<String>> wantOf,
      Map<String, Set<String>> actual) {}

  /** 잠금을 잡은 트랜잭션 안에서 계산값(등급·데이터셋)과 실제 ACL(카탈로그)을 읽는다 — 바꾸지는 않는다. 규칙은 예전 applyInTx 앞부분 그대로다. */
  private AclPlan planAcl(DSLContext ds, long tenantId, String onlyTable, Integer runRank) {
    String schema = DataSchema.current();
    List<String> slotRoleNames = TenantPipelineRole.pythonReadRoleNames(tenantId);
    List<LevelPolicy> levelsAsc = levelRepository.findAll(); // rank asc, 이 트랜잭션에 합류(RLS GUC)
    // 다룰 슬롯: 실행 준비면 실행 슬롯 하나(범위 밖이면 없음 — prepareForRun 이 거부), 아니면 전부.
    int runSlot = 0;
    List<String> targetRoles = new ArrayList<>();
    if (runRank == null) {
      targetRoles.addAll(slotRoleNames);
    } else {
      runSlot = PythonReadSlots.slotFor(levelsAsc, runRank);
      if (runSlot >= 1 && runSlot <= TenantPipelineRole.PYTHON_READ_SLOTS) {
        targetRoles.add(slotRoleNames.get(runSlot - 1));
      }
    }
    // 존재하는 슬롯 롤만 다룬다 — 없는 롤에 GRANT 하면 오류다. 없는 슬롯은 prepareForRun 이 거부한다.
    Set<String> existingRoles =
        targetRoles.isEmpty()
            ? Set.of()
            : new HashSet<>(
                ds.fetch(
                        "select rolname::text from pg_roles where rolname::text = any({0}::text[])",
                        DSL.val(targetRoles.toArray(new String[0])))
                    .getValues(0, String.class));
    Set<Integer> availableSlots = new TreeSet<>();
    for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
      if (existingRoles.contains(slotRoleNames.get(k - 1))) {
        availableSlots.add(k);
      }
    }

    if (existingRoles.isEmpty()) {
      return new AclPlan(
          schema,
          levelsAsc,
          slotRoleNames,
          existingRoles,
          availableSlots,
          runSlot,
          Map.of(),
          List.of(),
          t -> Set.of(),
          Map.of());
    }
    String[] roleArray = existingRoles.toArray(new String[0]);

    // 계산값: 데이터셋 테이블 → SELECT 를 받아야 할 슬롯 롤 집합. 테이블 하나만 맞출 때(생성·맞바꿈 훅)는 그 테이블의 데이터셋 행만 읽는다 —
    // 쓰기 경로마다 테넌트 데이터셋 전체를 읽지 않게(CR4).
    Map<String, Set<String>> desired = new HashMap<>();
    for (var r : datasetRepository.findTableLevels(onlyTable)) {
      Set<String> roles = new TreeSet<>();
      for (int k : PythonReadSlots.grantSlotsFor(levelsAsc, r.securityLevelId())) {
        String role = slotRoleNames.get(k - 1);
        if (existingRoles.contains(role)) {
          roles.add(role);
        }
      }
      desired.put(r.tableName(), roles);
    }

    // 실제: 스키마의 관계(테이블·뷰·구체화 뷰·외부 테이블)와 슬롯 롤에 걸린 SELECT.
    // 왜 뷰까지 보는가(CR5): 뷰는 소유자 권한으로 바닥 테이블을 읽는다 — 손으로(또는 SQL 스텝이) 슬롯 롤에 뷰 SELECT 를 걸면 등급 밖
    // 테이블을 그 뷰로 우회해 읽는다. 테이블만 보면 이 GRANT 를 탐지·회수하지 못한다. 시퀀스('S')는 행 데이터가 없어 제외한다.
    Map<String, Character> relkinds = new HashMap<>();
    // 관계 oid(WD-673) — 잠금을 못 잡았을 때 "그새 DROP(권한도 함께 사라짐)" 과 "RENAME·잠금 시간 초과(권한이 남음)" 를 가른다.
    // 이름은 RENAME 뒤 옛 이름으로 찾을 수 없어 가를 수 없고(WD-655 fail-open), oid 는 RENAME 해도 그대로다.
    Map<String, Long> oids = new HashMap<>();
    for (var r :
        ds.fetch(
            "select c.relname::text, c.relkind::text, c.oid::bigint from pg_class c"
                + " join pg_namespace n on n.oid = c.relnamespace"
                + " where n.nspname = {0} and c.relkind in ('r','p','v','m','f')"
                + (onlyTable == null ? "" : " and c.relname = {1}"),
            onlyTable == null
                ? new Object[] {DSL.val(schema)}
                : new Object[] {DSL.val(schema), DSL.val(onlyTable)})) {
      relkinds.put(r.get(0, String.class), r.get(1, String.class).charAt(0));
      oids.put(r.get(0, String.class), r.get(2, Long.class));
    }
    // 이름 오름차순(WD-629) — 아래 루프가 테이블마다 ACCESS SHARE 를 잡아 트랜잭션 끝까지 쥐므로, 해시 순서(HashMap)면 잠금 순서가
    // 비결정이라 잠금 순서 규칙(lockTableForAclChange 참고)을 세우거나 검증할 수 없다. Java 문자열 순서면 충분하다 — 이 동기화 내부의
    // 결정성용이라 DB collation 과 일치할 필요는 없다.
    List<String> physical = new ArrayList<>(new TreeSet<>(relkinds.keySet()));
    // 관계별 계산값 — 일반·분할 테이블이 아닌 관계(뷰 등)는 데이터셋 행이 같은 이름을 가리켜도 "없음"이다. 뷰 GRANT 는 소유자 권한 읽기라
    // 등급 경계를 지키지 못한다(fail-safe). 적용 루프와 재확인이 같은 규칙을 쓴다.
    Function<String, Set<String>> wantOf =
        t -> {
          char kind = relkinds.get(t);
          return kind == 'r' || kind == 'p' ? desired.getOrDefault(t, Set.of()) : Set.of();
        };
    Map<String, Set<String>> actual = new HashMap<>();
    for (var r : fetchSlotSelectGrants(ds, schema, physical, roleArray)) {
      actual
          .computeIfAbsent(r.get(0, String.class), x -> new TreeSet<>())
          .add(r.get(1, String.class));
    }
    return new AclPlan(
        schema,
        levelsAsc,
        slotRoleNames,
        existingRoles,
        availableSlots,
        runSlot,
        oids,
        physical,
        wantOf,
        actual);
  }

  /**
   * 이 계획을 적용하면 SELECT 를 <b>새로 받는</b> 슬롯 번호들(WD-701 4차) — 등급 정의 변경 동기화는 이 슬롯에만 "세션이 없어야 넓힌다" 를 건다.
   * 좁아지거나 그대로인 슬롯의 옛 세션은 새로 읽게 되는 것이 없다(PG 는 쿼리마다 권한을 본다).
   */
  private static Set<Integer> widenedSlots(AclPlan plan) {
    Set<Integer> widened = new TreeSet<>();
    for (String table : plan.physical()) {
      Set<String> missing = new TreeSet<>(plan.wantOf().apply(table));
      missing.removeAll(plan.actual().getOrDefault(table, Set.of()));
      for (String role : missing) {
        int k = plan.slotRoleNames().indexOf(role);
        if (k >= 0) {
          widened.add(k + 1);
        }
      }
    }
    return widened;
  }

  /** 계획의 차이를 적용한다 — 예전 applyInTx 뒷부분 그대로(잠금·savepoint·재확인). */
  private SyncResult applyPlan(
      DSLContext ds,
      long tenantId,
      boolean revokeOnly,
      Set<String> noWidenRoles,
      String schema,
      List<LevelPolicy> levelsAsc,
      Set<Integer> availableSlots,
      int runSlot,
      String[] roleArray,
      Map<String, Long> oids,
      List<String> physical,
      Function<String, Set<String>> wantOf,
      Map<String, Set<String>> actual) {
    int changed = 0;
    // 테이블 → 과권한이 남았을 수 있는 슬롯 롤
    Map<String, Set<String>> revokeFailed = new TreeMap<>();
    // 테이블 → REVOKE 가 성공한 슬롯 롤(WD-664 7 — 확인된 좁히기 뒤 끊을 슬롯)
    Map<String, Set<String>> revoked = new TreeMap<>();
    List<String> changedTables = new ArrayList<>();
    for (String table : physical) {
      // 데이터셋이 아닌 테이블(staging·REPLACE 임시 테이블 등)·뷰는 계산값이 "없음" — 남아 있는 슬롯 GRANT 는 회수한다.
      Set<String> want = wantOf.apply(table);
      Set<String> have = actual.getOrDefault(table, Set.of());
      if (want.equals(have)) {
        continue;
      }
      Set<String> excess = new TreeSet<>(have);
      excess.removeAll(want);
      Set<String> missing = new TreeSet<>(want);
      missing.removeAll(have);
      if (revokeOnly) {
        if (excess.isEmpty()) {
          continue; // 좁히기는 부족한 GRANT 를 건드리지 않는다 — 넓히기는 커밋 뒤 실행기가 한다
        }
        missing.clear();
      }
      // 미확인 표식 슬롯은 넓히지 않는다(재검토 1) — 회수는 그대로 한다.
      missing.removeAll(noWidenRoles);
      if (excess.isEmpty() && missing.isEmpty()) {
        continue;
      }
      String qualified = ds.render(DSL.name(schema, table));
      // ACL 을 고치기 전에 그 테이블에 ACCESS SHARE 잠금을 잡는다(이유는 lockTableForAclChange 참고).
      // 못 잡으면(시간 초과, 그새 DROP·RENAME) 이번에는 이 테이블을 건너뛴다.
      // 부족한 GRANT 는 과소권한이라 안전하다. 남는 권한은 회수 실패로 세어 prepareForRun 이 실행을 거부한다.
      if (!lockTableForAclChange(ds, qualified)) {
        // WD-673: 목록을 읽은 뒤 실제로 DROP 된 테이블(oid 가 사라짐)은 ACL 도 함께 사라졌다 — 과권한이 남지 않으므로 회수 실패로
        // 세지 않는다(예전에는 세어 그 동기화와 겹친 PYTHON 실행이 한 번 거부됐다). 아직 진행 중(미커밋)인 DROP 은 oid 가 보이므로
        // 아래 fail-closed 로 간다 — 되돌려질 수 있기 때문이다.
        if (!relationExists(ds, oids.get(table))) {
          log.info(
              "PYTHON 읽기 권한 동기화: 테이블이 그새 삭제돼 건너뛴다(권한도 함께 사라짐): tenant={} table={}",
              tenantId,
              table);
          continue;
        }
        // WD-655: 같은 oid 가 남아 있으면 RENAME(GRANT 가 새 이름으로 따라감)이거나 잠금 시간 초과다 — Tibero 판과 같은 근거로
        // fail-closed(회수 실패). 다음 동기화가 새 이름의 권한을 회수하면 거부가 풀린다.
        log.warn(
            "PYTHON 읽기 권한 동기화: 테이블 잠금을 얻지 못해 이번에는 건너뛴다(다음 동기화에서 재시도, 남는 권한은 회수 실패로 센다): tenant={}"
                + " table={}",
            tenantId,
            table);
        if (!excess.isEmpty()) {
          revokeFailed.computeIfAbsent(table, x -> new TreeSet<>()).addAll(excess);
        }
        continue;
      }
      // 테이블마다 savepoint 로 격리한다 — 테이블 하나의 오류(소유자 아님·동시 DROP·tuple concurrently updated)가 테넌트 전체
      // 동기화를 중단시키면 JIT 경로에서 그 테넌트의 모든 PYTHON 이 실패한다. REVOKE 와 GRANT 를 따로 감싸는 이유: GRANT 실패로 REVOKE
      // 까지 되돌리면 과권한이 남는다.
      if (!excess.isEmpty()
          && !inSavepoint(
              ds,
              () ->
                  ds.execute(
                      "REVOKE ALL ON TABLE " + qualified + " FROM " + quoteAll(ds, excess)))) {
        // REVOKE 문장 전체가 되돌려졌다 — excess 롤 전부에 과권한이 남았을 수 있다(그 슬롯으로 실행하면 prepareForRun 이 거부).
        revokeFailed.computeIfAbsent(table, x -> new TreeSet<>()).addAll(excess);
        continue;
      }
      if (!excess.isEmpty()) {
        revoked.computeIfAbsent(table, x -> new TreeSet<>()).addAll(excess);
      }
      if (!missing.isEmpty()
          && !inSavepoint(
              ds,
              () ->
                  ds.execute(
                      "GRANT SELECT ON TABLE " + qualified + " TO " + quoteAll(ds, missing)))) {
        // 과소권한(안전) — 스크립트가 그 테이블을 읽으면 permission denied. 다음 동기화가 재시도한다.
        log.warn("PYTHON 읽기 GRANT 실패(과소권한, 다음 동기화에서 재시도): tenant={} table={}", tenantId, table);
      }
      changed++;
      changedTables.add(table);
    }
    // 재확인: 소유자가 아닌 롤의 GRANT/REVOKE 는 일부 권한을 가진 경우 오류가 아니라 WARNING 만 내고 아무것도 하지 않는다. 그래서
    // savepoint 성공만으로는 회수를 믿을 수 없다 — 바꾼 테이블의 실제 ACL 을 다시 읽어 "원하지 않는 슬롯에 SELECT 가 남은" 테이블을 회수
    // 실패로 센다(과권한 → prepareForRun 이 실행 거부).
    if (!changedTables.isEmpty()) {
      for (var r : fetchSlotSelectGrants(ds, schema, changedTables, roleArray)) {
        String table = r.get(0, String.class);
        String role = r.get(1, String.class);
        if (!wantOf.apply(table).contains(role)) {
          revokeFailed.computeIfAbsent(table, x -> new TreeSet<>()).add(role);
        }
      }
    }
    if (changed > 0 || !revokeFailed.isEmpty()) {
      log.info(
          "PYTHON 읽기 권한 동기화: tenant={} 변경 테이블 {}개, 회수 실패 {}개",
          tenantId,
          changed,
          revokeFailed.size());
    }
    return new SyncResult(levelsAsc, availableSlots, changed, revokeFailed, runSlot, revoked);
  }

  /**
   * 관계 oid 가 아직 카탈로그에 있는가(WD-673) — 잠금 실패 뒤 "실제 DROP" 과 "RENAME·잠금 시간 초과" 를 가른다. 카탈로그 읽기는 문장마다 새
   * 스냅숏이라 그새 커밋된 DROP 이 보인다. oid 를 모르면(목록에 없던 이름) 있다고 본다(fail-closed).
   */
  private static boolean relationExists(DSLContext ds, Long oid) {
    return oid == null
        || Boolean.TRUE.equals(
            ds.fetchValue(
                "select exists(select 1 from pg_class where oid = {0}::oid)", DSL.val(oid)));
  }

  /** 주어진 테이블들에 슬롯 롤이 가진 SELECT 권한(테이블명, 롤명) 쌍. 테이블 목록이 비면 빈 결과. */
  private static Result<Record> fetchSlotSelectGrants(
      DSLContext ds, String schema, List<String> tables, String[] roles) {
    if (tables.isEmpty()) {
      return ds.newResult();
    }
    return ds.fetch(
        "select c.relname::text, r.rolname::text from pg_class c"
            + " join pg_namespace n on n.oid = c.relnamespace"
            + " cross join lateral aclexplode(c.relacl) a join pg_roles r on r.oid = a.grantee"
            + " where n.nspname = {0} and c.relname::text = any({1}::text[])"
            + " and a.privilege_type = 'SELECT' and r.rolname::text = any({2}::text[])",
        DSL.val(schema), DSL.val(tables.toArray(new String[0])), DSL.val(roles));
  }

  /** 롤 이름들을 인용 식별자 목록으로("a", "b"). 이름은 TenantPipelineRole 조립값이지만 그래도 인용한다. */
  private static String quoteAll(DSLContext ds, Set<String> roles) {
    return roles.stream().map(x -> ds.render(DSL.name(x))).collect(Collectors.joining(", "));
  }

  /**
   * ACL 을 바꿀 테이블에 ACCESS SHARE 잠금을 잡는다(트랜잭션 끝까지 유지). 잡았으면 true.
   *
   * <p><b>왜 잡는가.</b> GRANT/REVOKE 는 테이블 잠금 없이 pg_class 행(ACL)을 고친다. 그래서 같은 테이블의 DDL(ALTER
   * TABLE·DROP TABLE — ACCESS EXCLUSIVE)과 겹치면 늦게 커밋되는 쪽이 {@code tuple concurrently updated}(XX000)로
   * 깨진다. 커밋 뒤 동기화가 전용 실행기로 넘어가면서(WD-594) 데이터셋 생성 직후의 컬럼 추가·삭제와 실제로 겹쳐 사용자 요청이 500 이 됐다. ACCESS
   * SHARE 는 ACCESS EXCLUSIVE 와 충돌하므로 DDL 은 이 동기화의 커밋을 기다리고, 동기화는 진행 중인 DDL 을 기다린 뒤 새 카탈로그를 본다.
   * 읽기·쓰기(DML)와는 충돌하지 않는다.
   *
   * <p><b>잠금 순서.</b> 테넌트 동기화 잠금(advisory) → 테이블 잠금, 테이블끼리는 <b>이름 오름차순</b>(WD-629). 규칙: 여러 기존 테이블을 한
   * 트랜잭션에서 잠그는 앱 DDL 은 반드시 테넌트 동기화 잠금({@link #lockTenantSync})을 테이블 잠금보다 먼저 잡는다 — 그러면 동기화와 완전히
   * 직렬화되어 테이블 순서가 달라도 교착이 없다. 이름 정렬만으로는 부족하다: 재생성 되돌리기는 보관본({@code rtmp_bak_*}) → 원래 이름 순서로 잠그는데,
   * 사용자 테이블 이름은 그 앞뒤 어디든 올 수 있다. 이 순서를 지키는 곳: REPLACE 맞바꿈({@code
   * PostgresDataTableStore.swapTable}), 재생성 되돌리기({@code
   * PostgresDataTableStore.restoreRebuildBackup}), 그리고 {@code
   * DataTableStore.lockTenantGrantSyncForDdl} 을 먼저 부르는 경로 — 데이터셋 삭제(색인·원본·TEMP 보관본, {@code
   * DatasetService.deleteDataset}), 파이프라인의 임시 데이터셋 일괄 삭제({@code
   * TempDatasetService.deleteTempDatasets}), 재생성 보관(남은 보관본 DROP + 원본 RENAME, {@code
   * DataTableService.moveToRebuildBackup}), 보관본 버리기(보관본 + 색인, {@code
   * DatasetService.discardPipelineTempBackup}). 테이블 하나만 잠그는 DDL(컬럼 DDL·REPLACE 임시 테이블 정리 등)은 고리를
   * 만들지 않는다. 동기화 잠금은 트랜잭션 범위라 바깥 트랜잭션이 끝날 때까지 쥔다 — 그동안 같은 테넌트 동기화가 기다린다(알려진 성질). 잔여 위험: 사용자 SQL 스텝이
   * 임의 순서로 여러 테이블을 DDL 하는 경우는 통제할 수 없다 — PG 교착 탐지가 한쪽을 끊고, 동기화 쪽이 지면(40P01) savepoint 로 그 테이블을
   * 건너뛴다. 데이터셋 행 잠금은 advisory 앞이다 — 「데이터셋 행 → 동기화 → 테이블」(WD-666, {@code
   * TempDatasetService.deleteTempDatasets} 참고).
   *
   * <p><b>왜 짧은 lock_timeout 을 이 문장에만 거는가.</b> 오래 걸리는 DDL(큰 테이블 재작성) 뒤에서 실행 준비(prepareForRun)가 끝없이
   * 멈추지 않게, 또 같은 스레드가 쥔 다른 트랜잭션의 DDL 을 기다리는 교착(데이터베이스가 볼 수 없는 교착)이 생겨도 풀리게 한다. 테넌트 advisory 잠금 대기에는
   * 걸지 않는다 — 다른 동기화가 그 잠금을 쥐었다고 실행 준비가 실패하면 안 되므로 문장이 끝나면 이전 값으로 되돌린다.
   */
  private static boolean lockTableForAclChange(DSLContext ds, String qualified) {
    String previous = ds.fetchValue(DSL.field("current_setting('lock_timeout')", String.class));
    ds.execute("select set_config('lock_timeout', {0}, true)", DSL.val(TABLE_LOCK_TIMEOUT));
    try {
      return inSavepoint(ds, () -> ds.execute("LOCK TABLE " + qualified + " IN ACCESS SHARE MODE"));
    } finally {
      ds.execute("select set_config('lock_timeout', {0}, true)", DSL.val(previous));
    }
  }

  /**
   * 한 문장을 savepoint 안에서 실행한다. 실패하면 savepoint 로 되돌리고 false(트랜잭션은 계속 쓸 수 있다).
   *
   * <p>jOOQ 의 DataAccessException 도 잡는다 — Spring 의 SQLState 번역기는 모르는 SQLState(XX000 「tuple
   * concurrently updated」 등)를 번역하지 않아 jOOQ 예외가 그대로 나온다. Spring 예외만 잡으면 바로 그 오류가 savepoint 를 지나쳐 동기화
   * 트랜잭션 전체를 실패시킨다(실측).
   */
  private static boolean inSavepoint(DSLContext ds, Runnable statement) {
    ds.execute("SAVEPOINT python_read_sync");
    try {
      statement.run();
      ds.execute("RELEASE SAVEPOINT python_read_sync");
      return true;
    } catch (DataAccessException | org.jooq.exception.DataAccessException e) {
      ds.execute("ROLLBACK TO SAVEPOINT python_read_sync");
      log.warn("PYTHON 읽기 권한 문장 실패: {}", e.getMessage());
      return false;
    }
  }
}
