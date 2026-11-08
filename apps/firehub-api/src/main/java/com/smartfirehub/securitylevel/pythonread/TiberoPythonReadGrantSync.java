package com.smartfirehub.securitylevel.pythonread;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.dataset.storage.tibero.TiberoCatalog;
import com.smartfirehub.dataset.storage.tibero.TiberoDdlSql;
import com.smartfirehub.dataset.storage.tibero.TiberoNames;
import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantBackend.NarrowResult;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tibero 테넌트의 PYTHON 읽기 슬롯 계정({@code GENIA_T{id}_S1..S10})에 <b>테이블별 SELECT 권한</b>을 계산값에 맞춰
 * 동기화한다(WD-78, 스펙 §5.1). PG 판({@link PythonReadGrantSync})과 같은 구조 — 보안 등급 경계를 스크립트 코드가 아니라 DB 권한이
 * 지킨다.
 *
 * <p><b>계산값은 PG 와 같다.</b> 어느 슬롯이 어느 테이블을 읽는지는 {@link PythonReadSlots#grantSlotsFor}(위치 p 면 p..10,
 * 허용 목록 등급·10 초과 위치는 무권한)를 그대로 쓴다. 등급·데이터셋 메타는 PG 메타 DB 에 있으므로(RLS) 이 동기화도 PG 의 새 트랜잭션 안에서 돈다.
 *
 * <p><b>현재 권한.</b> OWNER 세션({@link ExternalStoreConnections#withDdl})에서 {@code USER_TAB_PRIVS_MADE}
 * 로 읽고 차이만 GRANT/REVOKE 한다. 데이터셋 행이 없는 테이블(스테이징 {@code STG_*}·뷰 등)의 계산값은 "없음"이라, 슬롯에 걸려 있으면 회수한다.
 * 슬롯 계정에는 처음부터 CREATE SESSION 만 있다({@code TiberoAccountPlan}).
 *
 * <p><b>직렬화.</b> 같은 테넌트의 동기화는 PG 메타의 트랜잭션 advisory 잠금({@link PythonReadGrantSync#lockTenantSync})으로
 * 직렬화한다 — PG 판과 같은 잠금이라 한 테넌트의 두 백엔드 동기화가 겹치지 않는다. Tibero 문장은 그 PG 트랜잭션이 끝날 때까지 잠금을 쥔 채 실행한다.
 *
 * <p><b>왜 테이블 잠금을 잡지 않는가(WD-629).</b> PG 판은 ACL 을 바꾸기 전에 테이블에 ACCESS SHARE 를 잡아 같은 테이블의 DDL 과
 * 직렬화한다({@code PythonReadGrantSync.lockTableForAclChange}). Oracle 계열(Tibero)에서는 GRANT/REVOKE 자체가
 * DDL 이라 실행 전 암묵 커밋으로 {@code LOCK TABLE} 잠금을 먼저 풀어 버리므로 같은 방법을 쓸 수 없다. Tibero 는 DDL 끼리 딕셔너리 잠금으로
 * 직렬화하고, 겹침 실측은 {@code TiberoLiveSlotGrantTest.grantOverlappingAlterAndDrop_doesNotFailUserDdl} 이
 * 맡는다. 겹쳐서 동기화 쪽 문장이 실패하면 GRANT 실패는 과소권한(안전), REVOKE 실패는 아래 재판정을 거쳐 남은 권한이 있으면 회수 실패(실행 거부)가 되고 다음
 * 동기화가 회복한다. 앱 수준 직렬화(DBMS_LOCK·JVM 잠금)는 Tibero DDL 경로 전부에 잠금을 끼워야 하고 교착 위험을 라이브로 검증할 수 없어 택하지
 * 않았다(WD-645).
 *
 * <p><b>재판정 규칙(WD-629 → WD-645).</b> Tibero 는 USER_TABLES 에서 이름이 사라져도 권한이 사라졌다는 보장이 없다 — 재생성은 테이블을
 * 보관본 이름으로 RENAME 하고({@code TiberoRebuildBackup}) 휴지통은 DROP 된 테이블을 {@code BIN$...} 객체로 남기며, GRANT 는
 * 둘 다 따라간다. 그래서 "이름이 안 보이면 넘어간다" 는 완화는 쓰지 않는다. 대신 첫 판에 휴지통 객체가 아닌 회수 실패가 있으면 같은 연결·PG 트랜잭션·테넌트 잠금
 * 안에서 <b>한 번만</b> 대상 계정 그대로, 테이블 범위만 테넌트 전체로 넓혀 다시 맞추고, 그 판의 결과(다시 읽은 실제 권한 기준)가 첫 판의 실패 목록을 바꾼다.
 * 판정이 이름이 아니라 남은 권한이므로 fail-open 이 아니다 — 권한까지 사라졌거나 새 이름으로 회수되면 실패가 아니고, 권한이 남으면 여전히 회수
 * 실패다(fail-closed). 휴지통 객체 실패만 있으면 OWNER 가 회수할 수 없어 다시 해도 같으므로 재판정하지 않는다(실패는 남는다). 실패가 없으면 추가
 * 조회·문장이 없고, 대기·세 번째 시도도 없다(다음 동기화가 다시 맞춘다).
 *
 * <p><b>휴지통 권한 가시성(WD-645).</b> 위 fail-closed 는 {@code USER_TAB_PRIVS_MADE}({@link
 * TiberoCatalog#slotSelectGrants})가 {@code BIN$...} 객체의 GRANT 도 보여 준다는 데 기댄다 — 라이브 TC {@code
 * TiberoLiveSlotGrantTest.droppedTableInRecycleBin_slotGrantStillListed} 가 확인한다. FLASHBACK 으로 되살아난
 * GRANT 도, 모든 실행 준비({@link #prepareForRun})가 실행 전에 실행 슬롯을 테넌트 전체 범위로 다시 맞추므로 쓰이기 전에 회수된다(못 하면 실행
 * 거부).
 *
 * <p><b>세션 종료는 하지 않는다(스펙 §5.1).</b> 스크립트는 슬롯 연결을 쥐지 않고 읽을 때마다 db-executor 가 새 문장을 실행하므로 REVOKE 가 다음
 * 조회부터 반영된다. OWNER 에게는 세션 종료 권한도 없다. 그래서 등급 정의 변경 동기화도 일반 전체 동기화와 같다.
 */
@Slf4j
@Service
public class TiberoPythonReadGrantSync implements PythonReadGrantBackend {

  private final DatasetRepository datasetRepository;
  private final ExternalStoreConnections connections;
  private final TenantDataStoreRepository storeRepository;
  private final SecurityLevelRepository levelRepository;
  private final TransactionTemplate requiresNew;

  /** 테넌트 동기화 직렬화 잠금 — 플랫폼 잠금 추상화(WD-486). */
  private final PlatformLocks locks;

  /** 커밋 뒤 테이블 동기화를 돌리는 전용 실행기(PG 판과 공용) — 커밋 콜백 스레드가 커넥션 2개를 쥐지 않게 한다(WD-594). */
  private final PythonReadSyncExecutor afterCommitExecutor;

  /**
   * txManager 는 @Primary TenantAwareTransactionManager — 트랜잭션 시작 때 TenantContext 를 RLS GUC 로 주입한다.
   */
  public TiberoPythonReadGrantSync(
      DatasetRepository datasetRepository,
      ExternalStoreConnections connections,
      TenantDataStoreRepository storeRepository,
      SecurityLevelRepository levelRepository,
      PlatformLocks locks,
      PlatformTransactionManager txManager,
      PythonReadSyncExecutor afterCommitExecutor) {
    this.datasetRepository = datasetRepository;
    this.afterCommitExecutor = afterCommitExecutor;
    this.locks = locks;
    this.connections = connections;
    this.storeRepository = storeRepository;
    this.levelRepository = levelRepository;
    this.requiresNew = new TransactionTemplate(txManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** 현재 테넌트 전체 재동기화. 실패는 예외(호출부가 로그/실패를 정한다). */
  @Override
  public PythonReadGrantSync.SyncResult syncTenant() {
    return requiresNew.execute(status -> syncInTx(null, null));
  }

  /**
   * 등급 정의 변경(순서 변경·삭제·허용 목록 해제) 뒤 전체 재동기화.
   *
   * <p>PG 는 새 GRANT 를 커밋하기 전에 슬롯 롤 세션을 끊어 실행 중인 스크립트가 넓어진 슬롯으로 자격 밖 등급을 읽지 못하게 한다. Tibero 는 세션을 끊을
   * 수 없으므로 (OWNER 에 권한이 없다, 스펙 §5.1) 대신 <b>스크립트 토큰 세대 번호</b>(Ruling 15)를 올린다: GRANT 를 하나라도 걸기
   * <b>전에</b> 번호를 올리고 그 변경을 별도 트랜잭션으로 <b>먼저 커밋</b>해 기술자를 읽는 쪽이 새 번호를 보게 한다. 스크립트 토큰(Task 13)이 옛 번호를
   * 싣고 있으면 db-executor 가 거부하므로 이미 실행 중이던 스크립트의 다음 읽기가 막힌다. 번호를 올리지 못하면 예외로 끝낸다 — 넓히는 GRANT 는 걸지
   * 않는다.
   *
   * <p>등급 변경 트랜잭션도 커밋 직전에 번호를 이미 한 번 올렸다({@link #bumpScriptTokenEpochInCurrentTx}, WD-624). 여기서 한 번
   * 더 올리는 이유는 커밋 직전에 옛 등급으로 준비한 실행이 커밋 뒤에 새 번호를 읽어 토큰을 받았을 수 있기 때문이다 — 그 토큰을 넓히는 GRANT 전에 무효로 만든다.
   */
  @Override
  public PythonReadGrantSync.SyncResult syncTenantForLevelsChange() {
    long tenantId = TenantContext.require("Tibero 등급 정의 변경 동기화");
    // 별도 트랜잭션: 반환 시점에 이미 커밋돼 있어야 한다(아래 GRANT 동기화와 같은 트랜잭션이면 커밋 순서가 보장되지 않는다).
    Optional<Long> epoch =
        requiresNew.execute(status -> storeRepository.incrementScriptTokenEpoch(tenantId));
    if (epoch == null || epoch.isEmpty()) {
      log.warn("Tibero 저장소 행이 없어 스크립트 토큰 세대 번호를 올리지 못했다(tenant={})", tenantId);
    } else {
      log.info("등급 정의 변경으로 스크립트 토큰 세대 번호를 올렸다: tenant={} epoch={}", tenantId, epoch.get());
    }
    return syncTenant();
  }

  /**
   * 스크립트 토큰 세대 번호를 <b>현재 트랜잭션에서</b> 올린다(WD-624) — 등급 정의 변경(또는 좁히기 실패) 트랜잭션의 커밋 직전에 리스너가 부른다.
   *
   * <p><b>왜 현재 트랜잭션인가.</b> 넓히는 GRANT(전체 동기화)는 커밋 뒤 전용 실행기에서 돈다. 세대 번호까지 그 작업에 두면 큐가 빌 때까지 옛 토큰을 쥔
   * 실행 중 스크립트가 좁아진 슬롯의 테이블을 계속 읽는다(Tibero 는 세션을 끊을 수 없다). 같은 트랜잭션이면 번호 올림이 등급 변경과 <b>함께</b> 커밋되므로,
   * 커밋 응답이 나가는 순간부터 옛 토큰의 다음 읽기가 거부되고, 새 커넥션도 필요 없다(요청당 커넥션 1개). 실패하면 예외 — 등급 변경도 커밋되지 않는다
   * (fail-closed).
   *
   * <p>테넌트 저장소 행이 없으면(PG 테넌트) 아무것도 바꾸지 않는다 — 그래서 부르는 쪽이 백엔드를 묻지 않고 늘 불러도 된다. 실행기의 {@link
   * #syncTenantForLevelsChange} 는 GRANT 직전에 번호를 한 번 더 올린다(커밋 직전에 옛 등급으로 준비한 실행이 커밋 뒤에 번호를 읽었을 수
   * 있다).
   *
   * @return 올린 뒤 번호(행이 없으면 empty)
   */
  public Optional<Long> bumpScriptTokenEpochInCurrentTx(long tenantId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("스크립트 토큰 세대 번호는 등급 변경 트랜잭션 안에서만 올린다");
    }
    Optional<Long> epoch = storeRepository.incrementScriptTokenEpoch(tenantId);
    epoch.ifPresent(
        e -> log.info("커밋 직전 스크립트 토큰 세대 번호를 올렸다(등급 변경과 함께 커밋): tenant={} epoch={}", tenantId, e));
    return epoch;
  }

  /**
   * 데이터셋 등급 변경을 커밋하기 직전, 그 트랜잭션에서 데이터셋 테이블의 남는 슬롯 권한만 회수한다(WD-624) — PG 판 {@link
   * PythonReadGrantSync#narrowDatasetInCurrentTx} 와 같은 규칙(방향을 따지지 않는 회수 전용, 짧은 잠금 대기, "더는 읽으면 안 되는
   * 슬롯" 을 결과에 싣는다). Tibero 문장은 OWNER 연결에서 바로 반영되므로 커밋 응답 전에 실행 중 스크립트의 다음 읽기부터 막힌다(등급 변경이 롤백돼도 남는
   * 것은 회수뿐이라 안전 — 다음 동기화가 되살린다). 파사드가 savepoint 안에서 부른다.
   */
  @Override
  public NarrowResult narrowDatasetInCurrentTx(long datasetId, Long fromLevelId, long toLevelId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("데이터셋 등급 좁히기는 등급 변경 트랜잭션 안에서만 부른다");
    }
    long tenantId = TenantContext.require("Tibero 데이터셋 등급 좁히기");
    Set<Integer> exposed =
        PythonReadSlots.slotsLosingAccess(levelRepository.findAll(), fromLevelId, toLevelId);
    String table = datasetRepository.findTableNameById(datasetId).orElse(null);
    if (table == null) {
      return NarrowResult.confirmed(exposed);
    }
    if (!locks.tryXactLock(
        PythonReadGrantSync.syncLockKey(tenantId), PythonReadGrantSync.NARROW_LOCK_WAIT)) {
      log.warn("Tibero 데이터셋 등급 좁히기: 테넌트 동기화 잠금을 얻지 못했다(tenant={} dataset={})", tenantId, datasetId);
      return NarrowResult.unconfirmed(exposed);
    }
    return PythonReadGrantBackend.narrowResultOf(
        applyInTx(tenantId, table, null, true),
        exposed,
        levelRepository::findAll,
        toLevelId,
        k -> slotAccount(tenantId, k));
  }

  /**
   * 커밋 직전 좁히기를 확인하지 못한 데이터셋 등급 변경의 뒷정리(실행기, WD-624) — 잠금 안에서 그 테이블을 계산값으로 맞춘다(회수 포함). Tibero 는 문장마다
   * 권한을 보므로 회수가 커밋되면(DDL 즉시 반영) 실행 중 스크립트의 다음 읽기부터 막힌다.
   *
   * <p><b>스크립트 토큰은 테넌트 단위다.</b> 세대 번호는 db-executor 가 테넌트별로만 비교하므로 슬롯 하나만 무효로 할 수 없다(슬롯별 번호는 저장소
   * 스키마와 db-executor 를 함께 바꿔야 한다). 그래서 테넌트 단위 번호 올리기는 꼭 필요할 때만 한다 — 커밋 직전에 올리지 못했거나({@code
   * tokensInvalidated} false) 여기서 회수가 실패해 그 슬롯에 권한이 남을 때.
   */
  @Override
  public void syncDatasetAfterUnconfirmedNarrowing(
      long datasetId, Set<Integer> exposedSlots, boolean tokensInvalidated) {
    long tenantId = TenantContext.require("Tibero 데이터셋 좁히기 뒷정리");
    if (!tokensInvalidated) {
      bumpEpochInNewTx(tenantId, "커밋 직전 번호 올리기 실패");
    }
    PythonReadGrantSync.SyncResult result =
        PythonReadGrantBackend.syncDatasetTable(
            requiresNew, datasetRepository, datasetId, this::syncTable);
    if (result == null) {
      return; // 물리 테이블이 없는 데이터셋 — 회수할 권한이 없다
    }
    Set<Integer> left =
        PythonReadGrantBackend.slotsOf(result.revokeFailed(), k -> slotAccount(tenantId, k));
    if (!left.isEmpty()) {
      log.error(
          "Tibero 데이터셋 좁히기 뒷정리: 회수 실패가 남았다 — 스크립트 토큰을 무효로 한다(tenant={} dataset={} slots={})",
          tenantId,
          datasetId,
          left);
      bumpEpochInNewTx(tenantId, "회수 실패");
    }
  }

  /** 새 트랜잭션에서 세대 번호를 올리고 커밋한다(실행기 경로). 실패는 예외. */
  private void bumpEpochInNewTx(long tenantId, String reason) {
    Optional<Long> epoch =
        requiresNew.execute(status -> storeRepository.incrementScriptTokenEpoch(tenantId));
    log.warn("스크립트 토큰 세대 번호를 올렸다({}): tenant={} epoch={}", reason, tenantId, epoch);
  }

  /** 슬롯 k 의 Tibero 저장소 계정 이름 — 회수 실패 결과에서 슬롯을 찾는 데 쓴다({@link PythonReadGrantBackend#slotsOf}). */
  private static String slotAccount(long tenantId, int slot) {
    return DataStoreAccountRole.forSlot(slot).usernameFor(tenantId);
  }

  /** 테이블 하나만 재동기화(생성·클론 직후, 데이터셋 등급 변경). 이름은 소문자 메타데이터 이름이다. */
  public PythonReadGrantSync.SyncResult syncTable(String tableName) {
    return requiresNew.execute(status -> syncInTx(tableName, null));
  }

  /** 데이터셋 하나 — 테이블 이름을 찾아 syncTable. 물리 테이블이 없는 데이터셋은 할 일이 없다. */
  @Override
  public void syncDataset(long datasetId) {
    PythonReadGrantBackend.syncDatasetTable(
        requiresNew, datasetRepository, datasetId, this::syncTable);
  }

  /**
   * 쓰기 경로 훅 — PG 판과 같이 트랜잭션 안이면 커밋 뒤 {@link PythonReadSyncExecutor} 에서 비동기로, 아니면 즉시 syncTable 하고
   * 실패는 로그만 남긴다. 공통부는 {@link PythonReadGrantBackend#scheduleTableSync}.
   */
  @Override
  public void syncTableAfterCommit(String tableName) {
    PythonReadGrantBackend.scheduleTableSync(
        tableName, log, "Tibero ", afterCommitExecutor, this::syncTable);
  }

  /**
   * PYTHON 실행 직전 준비(JIT) — PG 판과 같은 규칙({@link PythonReadRunGate})으로, 실행 슬롯 계정 하나의 SELECT 권한만 계산값에
   * 맞추고 슬롯 번호를 돌려준다. 어떤 실패든 {@link PythonReadAccessException}(fail-closed).
   *
   * @return 실행에 쓸 슬롯 번호(1~10)
   */
  @Override
  public int prepareForRun(Clearance runAs, int readRank) {
    return PythonReadRunGate.prepare(
        runAs,
        readRank,
        () -> requiresNew.execute(status -> syncInTx(null, readRank)),
        slot -> DataStoreAccountRole.forSlot(slot).usernameFor(runAs.tenantId()));
  }

  /**
   * Tibero 쪽에서 한 번에 읽고 적용한 결과. 바꾼 테이블은 이름 집합으로 둔다 — 재판정(WD-645)이 같은 테이블을 다시 바꿔도 두 판의 합집합 크기로 세어 두 번
   * 세지 않는다.
   */
  private record Applied(Set<String> changedTables, Map<String, Set<String>> revokeFailed) {}

  /**
   * 트랜잭션(=테넌트 GUC) 안에서 호출된다.
   *
   * @param onlyTable null 이면 테넌트 전체, 아니면 그 테이블만(소문자 메타데이터 이름)
   * @param runRank null 이면 슬롯 계정 전부, 아니면 그 자격 rank 의 실행 슬롯 계정 하나만(실행 준비)
   */
  private PythonReadGrantSync.SyncResult syncInTx(String onlyTable, Integer runRank) {
    long tenantId = TenantContext.require("Tibero PYTHON 읽기 권한 동기화");
    // 같은 테넌트 동기화 직렬화(트랜잭션 종료 시 자동 해제) — PG 판(lockTenantSync)과 같은 syncLockKey 를 써서 같은 잠금이다(WD-486
    // R4).
    locks.xactLock(PythonReadGrantSync.syncLockKey(tenantId));
    return applyInTx(tenantId, onlyTable, runRank, false);
  }

  /**
   * 잠금을 잡은 뒤의 본체 — 동기화({@link #syncInTx})와 커밋 직전 좁히기({@link #narrowDatasetInCurrentTx})가 함께 쓴다.
   *
   * @param revokeOnly true 면 남는 권한 회수만 하고 부족한 GRANT 는 걸지 않는다(넓히기는 커밋 뒤 실행기 몫)
   */
  private PythonReadGrantSync.SyncResult applyInTx(
      long tenantId, String onlyTable, Integer runRank, boolean revokeOnly) {
    List<LevelPolicy> levelsAsc = levelRepository.findAll();
    // 슬롯 k(1~10)의 계정 이름과, 저장소 계정 행이 실제로 있는 슬롯(없으면 PG 의 "없는 슬롯 롤" 과 같이 취급).
    List<String> slotAccounts = new ArrayList<>();
    for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
      slotAccounts.add(DataStoreAccountRole.forSlot(k).usernameFor(tenantId));
    }
    Set<Integer> availableSlots = new TreeSet<>();
    // 저장소가 READY 일 때만 계정 행을 본다 — PENDING 은 접속 자체가 불가하고 슬롯이 준비되지 않은 것과 같다.
    Optional<TenantDataStoreRow> row = storeRepository.find(tenantId);
    if (row.isPresent() && row.get().status() == TenantDataStoreStatus.READY) {
      Set<String> stored = new TreeSet<>();
      for (StoredAccount a : storeRepository.findAccounts(tenantId)) {
        stored.add(a.username());
      }
      for (int k = 1; k <= TenantPipelineRole.PYTHON_READ_SLOTS; k++) {
        if (stored.contains(slotAccounts.get(k - 1))) {
          availableSlots.add(k);
        }
      }
    }

    // 다룰 슬롯: 실행 준비면 실행 슬롯 하나(범위 밖이면 없음 — prepareForRun 이 거부), 아니면 전부.
    int runSlot = 0;
    Set<Integer> targetSlots = new TreeSet<>();
    if (runRank == null) {
      targetSlots.addAll(availableSlots);
    } else {
      runSlot = PythonReadSlots.slotFor(levelsAsc, runRank);
      if (availableSlots.contains(runSlot)) {
        targetSlots.add(runSlot);
      }
    }
    if (targetSlots.isEmpty()) {
      if (runRank == null) {
        log.warn("Tibero PYTHON 읽기 슬롯 계정이 없다 — 동기화 생략(tenant={})", tenantId);
      }
      return new PythonReadGrantSync.SyncResult(levelsAsc, availableSlots, 0, Map.of(), runSlot);
    }
    Set<String> targetAccounts = new TreeSet<>();
    for (int k : targetSlots) {
      targetAccounts.add(slotAccounts.get(k - 1));
    }

    // 계산값: 데이터셋 테이블(물리 이름) → SELECT 를 받아야 할 슬롯 계정 집합. 테이블 하나만 맞출 때는 그 데이터셋 행만 읽는다.
    Map<String, Set<String>> desired =
        desiredGrants(onlyTable, levelsAsc, slotAccounts, targetAccounts);

    String onlyPhysical = onlyTable == null ? null : TiberoNames.physical(onlyTable);
    boolean exact = runRank != null;
    Applied applied =
        connections.withDdl(
            tenantId,
            conn -> {
              String schema = connections.schemaOf(tenantId);
              Applied first =
                  applyPass(
                      conn,
                      tenantId,
                      schema,
                      onlyPhysical,
                      desired,
                      targetAccounts,
                      exact,
                      revokeOnly);
              if (first.revokeFailed().keySet().stream().allMatch(TiberoNames::isRecycleBinName)) {
                // 실패 없음·휴지통 객체 실패뿐이면 재판정하지 않는다 — 클래스 Javadoc "재판정 규칙"(WD-645).
                return first;
              }
              // 재판정(WD-645): 한 번만, 대상 계정 그대로 테이블 범위만 전체로 — 클래스 Javadoc "재판정 규칙". 전체 범위 계산값은 이미 있으면
              // 재사용.
              Map<String, Set<String>> desiredAll =
                  onlyTable == null
                      ? desired
                      : desiredGrants(null, levelsAsc, slotAccounts, targetAccounts);
              Applied second =
                  applyPass(
                      conn, tenantId, schema, null, desiredAll, targetAccounts, exact, revokeOnly);
              // 실패 목록은 재판정 결과로 바꾸고, 바꾼 테이블은 두 판의 합집합으로 센다(두 번 세지 않는다).
              Set<String> changedUnion = new TreeSet<>(first.changedTables());
              changedUnion.addAll(second.changedTables());
              return new Applied(changedUnion, second.revokeFailed());
            });
    if (!applied.revokeFailed().isEmpty()) {
      // 회수 실패는 과권한이 남았을 수 있다는 뜻 — 운영자가 봐야 하므로 warn.
      log.warn(
          "Tibero PYTHON 읽기 권한 회수 실패: tenant={} 변경 테이블 {}개, 회수 실패 {}개 {}",
          tenantId,
          applied.changedTables().size(),
          applied.revokeFailed().size(),
          applied.revokeFailed());
    } else if (!applied.changedTables().isEmpty()) {
      log.info(
          "Tibero PYTHON 읽기 권한 동기화: tenant={} 변경 테이블 {}개",
          tenantId,
          applied.changedTables().size());
    }
    return new PythonReadGrantSync.SyncResult(
        levelsAsc, availableSlots, applied.changedTables().size(), applied.revokeFailed(), runSlot);
  }

  /**
   * 계산값 {물리 테이블 → SELECT 를 받아야 할 대상 슬롯 계정 집합}을 PG 메타의 데이터셋 행으로 만든다.
   *
   * <p>임시 데이터셋(ptmp_*)도 PG 와 마찬가지로 데이터셋 행이 있으므로 자기 등급대로 계산된다. 스테이징(STG_*)은 데이터셋이 아니라 행이 없다 → "없음".
   * 대상 계정 밖의 슬롯은 넣지 않는다(실행 준비는 실행 슬롯 하나만 다룬다).
   *
   * @param onlyTable null 이면 전체 데이터셋, 아니면 그 테이블의 데이터셋 행만(소문자 메타데이터 이름)
   */
  private Map<String, Set<String>> desiredGrants(
      String onlyTable,
      List<LevelPolicy> levelsAsc,
      List<String> slotAccounts,
      Set<String> targetAccounts) {
    Map<String, Set<String>> desired = new HashMap<>();
    for (var r : datasetRepository.findTableLevels(onlyTable)) {
      Set<String> accounts = new TreeSet<>();
      for (int k : PythonReadSlots.grantSlotsFor(levelsAsc, r.securityLevelId())) {
        String account = slotAccounts.get(k - 1);
        if (targetAccounts.contains(account)) {
          accounts.add(account);
        }
      }
      desired.put(TiberoNames.physical(r.tableName()), accounts);
    }
    return desired;
  }

  /**
   * 한 판의 적용: 테이블 목록과 대상 계정의 실제 SELECT 권한을 읽어 계산값과의 차이만 REVOKE/GRANT 하고, 바꾼 테이블은 실제 권한을 다시 읽어 남은
   * 과권한을 회수 실패로 센다. 첫 판과 재판정(WD-645)이 같은 규칙을 쓰도록 하나로 둔다.
   *
   * @param onlyPhysical null 이면 테넌트 전체, 아니면 그 물리 테이블만
   * @param exact 실행 준비(대상 계정을 정확히 바인드해 읽는다)
   * @param revokeOnly 커밋 직전 좁히기(WD-624) — 남는 권한 회수만 하고 부족한 GRANT 는 걸지 않는다. 재판정(WD-645)도 같은 값으로 돌아
   *     좁히기 경로가 재판정 중에 넓히는 GRANT 를 걸지 않는다
   */
  private static Applied applyPass(
      java.sql.Connection conn,
      long tenantId,
      String schema,
      String onlyPhysical,
      Map<String, Set<String>> desired,
      Set<String> targetAccounts,
      boolean exact,
      boolean revokeOnly)
      throws SQLException {
    Set<String> tables = TiberoCatalog.ownTables(conn, onlyPhysical);
    Map<String, Set<String>> actual =
        slotGrants(conn, tenantId, onlyPhysical, targetAccounts, exact);
    // 테이블이 아닌 객체(뷰 등)의 GRANT 도 훑는다 — 뷰는 소유자 권한으로 바닥 테이블을 읽어 등급 경계를 우회하므로 "없음" 으로 회수한다(PG
    // CR5).
    Set<String> names = new TreeSet<>(tables);
    names.addAll(actual.keySet());
    Map<String, Set<String>> failed = new TreeMap<>();
    Set<String> changedTables = new TreeSet<>();
    for (String table : names) {
      Set<String> want = tables.contains(table) ? desired.getOrDefault(table, Set.of()) : Set.of();
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
          continue; // 좁히기는 부족한 GRANT 를 건드리지 않는다
        }
        missing.clear();
      }
      // REVOKE 는 남는 권한이 있을 때만 한다(없는 권한을 회수하면 오류). 실패하면 그 계정들에 과권한이 남았을 수 있다.
      boolean revokeOk = true;
      for (String account : excess) {
        if (!execute(conn, TiberoDdlSql.revokeSelectFromSlot(schema, table, account))) {
          revokeOk = false;
          failed.computeIfAbsent(table, x -> new TreeSet<>()).add(account);
        }
      }
      if (!revokeOk) {
        // 이 판에서는 늘 회수 실패(fail-closed) — 첫 판 실패는 재판정이 남은 권한으로 다시 가린다(클래스 Javadoc "재판정 규칙", WD-645).
        continue;
      }
      for (String account : missing) {
        if (!execute(conn, TiberoDdlSql.grantSelectToSlot(schema, table, account))) {
          // 과소권한(안전) — 읽으면 거부된다. 다음 동기화가 재시도한다.
          log.warn(
              "Tibero PYTHON 읽기 GRANT 실패(과소권한, 다음 동기화에서 재시도): tenant={} table={}", tenantId, table);
        }
      }
      changedTables.add(table);
    }
    // 재확인: 문장이 성공해도 실제 권한을 다시 읽어 "원하지 않는 슬롯에 SELECT 가 남은" 테이블을 회수 실패로 센다.
    if (!changedTables.isEmpty()) {
      Map<String, Set<String>> after =
          slotGrants(conn, tenantId, onlyPhysical, targetAccounts, exact);
      for (String table : changedTables) {
        Set<String> want =
            tables.contains(table) ? desired.getOrDefault(table, Set.of()) : Set.of();
        for (String account : after.getOrDefault(table, Set.of())) {
          if (!want.contains(account)) {
            failed.computeIfAbsent(table, x -> new TreeSet<>()).add(account);
          }
        }
      }
    }
    return new Applied(changedTables, failed);
  }

  /**
   * 대상 슬롯 계정에 걸린 SELECT 권한만 남긴 {테이블 → 계정 집합}.
   *
   * <p>실행 준비({@code exact}, 실행 슬롯 계정 하나)는 그 계정을 정확히 바인드해 읽는다 — PYTHON 실행마다 도는 경로라 테넌트 슬롯 전부를 LIKE 로
   * 읽고 버리지 않는다. 전체·테이블 동기화는 LIKE 로 테넌트 슬롯 계정 전부를 읽고, LIKE 가 넓게 잡은 이름({@code GENIA_T1_SXYZ} 등)과 대상이
   * 아닌 슬롯은 여기서 거른다. 두 방식의 결과는 같다.
   */
  private static Map<String, Set<String>> slotGrants(
      java.sql.Connection conn,
      long tenantId,
      String onlyPhysical,
      Set<String> targetAccounts,
      boolean exact)
      throws SQLException {
    if (exact) {
      return TiberoCatalog.slotSelectGrants(conn, targetAccounts, onlyPhysical);
    }
    Map<String, Set<String>> all = TiberoCatalog.slotSelectGrants(conn, tenantId, onlyPhysical);
    Map<String, Set<String>> filtered = new HashMap<>();
    all.forEach(
        (table, accounts) -> {
          Set<String> kept = new TreeSet<>(accounts);
          kept.retainAll(targetAccounts);
          if (!kept.isEmpty()) {
            filtered.put(table, kept);
          }
        });
    return filtered;
  }

  /** 한 문장을 실행한다. 실패는 false(다른 테이블 처리를 막지 않는다 — 한 테이블의 오류가 테넌트 전체 동기화를 중단시키면 안 된다). */
  private static boolean execute(java.sql.Connection conn, String sql) {
    try (Statement st = conn.createStatement()) {
      st.execute(sql);
      return true;
    } catch (SQLException e) {
      log.warn("Tibero PYTHON 읽기 권한 문장 실패: code={} {}", e.getErrorCode(), e.getMessage());
      return false;
    }
  }
}
