package com.smartfirehub.platform.service;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreFeatures;
import com.smartfirehub.dataset.storage.DataStorePasswordGenerator;
import com.smartfirehub.dataset.storage.DataStoreProvisionMode;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.dataset.storage.external.AccountCheck;
import com.smartfirehub.dataset.storage.external.ConnectionTestResult;
import com.smartfirehub.dataset.storage.external.DataStoreConnectionTester;
import com.smartfirehub.dataset.storage.external.ExternalAccountProvisioner;
import com.smartfirehub.dataset.storage.external.ExternalDbTarget;
import com.smartfirehub.dataset.storage.tibero.PlannedAccount;
import com.smartfirehub.dataset.storage.tibero.TiberoAccountPlan;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.platform.dto.DataStoreBackendOption;
import com.smartfirehub.platform.dto.DataStoreConnectionTestRequest;
import com.smartfirehub.platform.dto.DataStoreScriptResponse;
import com.smartfirehub.platform.dto.DataStoreSetupRequest;
import com.smartfirehub.platform.dto.DataStoreVerifyResponse;
import com.smartfirehub.platform.dto.DbaCredentials;
import com.smartfirehub.platform.dto.TenantDataStoreResponse;
import com.smartfirehub.platform.exception.TenantNotFoundException;
import com.smartfirehub.platform.repository.PlatformTenantRepository;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 테넌트 생성 시 외부 데이터셋 저장소(Tibero) 선택을 처리한다(WD-73).
 *
 * <p>흐름은 두 번의 호출이다. {@link #validate} 는 테넌트 행을 만들기 전에 요청을 거르고, {@link #setUp} 은 계정 계획을 만들어 암호화해
 * 저장(테넌트와 같은 트랜잭션)한 뒤 DBA_AUTO 면 고객 DB 에 실제 계정을 만든다. 계획·비밀번호·DBA 자격은 {@link #setUp} 안에서만 오가고 호출자는
 * 감사 details 와 SCRIPT 스크립트만 받는다. 외부 생성은 DB 트랜잭션으로 되돌릴 수 없으므로, 그 뒤 트랜잭션이 롤백되면 {@code
 * afterCompletion} 에서 보상 DROP 을 한다. 보상 수단이 있으므로 {@link #setUp} 은 보상 수단이 없는 PostgreSQL 파이프라인 롤 생성보다
 * <b>먼저</b> 불린다(호출 순서는 {@code PlatformTenantService#create} 참고).
 *
 * <p>비밀(DBA 비밀번호·생성 비밀번호·암호문)은 로그·감사·예외 메시지에 넣지 않는다. 유일한 예외는 SCRIPT 모드의 스크립트 본문이다.
 */
@Slf4j
@Service
public class TenantDataStoreSetupService {

  private final TenantDataStoreRepository repository;
  private final DataStoreFeatures features;
  private final DataStorePasswordGenerator passwordGenerator;
  private final EncryptionService encryptionService;
  private final ExternalAccountProvisioner provisioner;
  private final PlatformTenantRepository tenantRepository;
  private final PlatformAuditRecorder auditRecorder;
  private final DataStoreConnectionTester connectionTester;
  private final DataStoreResolver resolver;

  /** {@link #verify} 의 상태 전환·감사용 짧은 쓰기 트랜잭션. 외부 접속은 이 바깥에서 한다. */
  private final TransactionTemplate writeTx;

  /** {@link #verify} 의 저장소·계정 스냅숏용 짧은 읽기 전용 트랜잭션. */
  private final TransactionTemplate readOnlyTx;

  public TenantDataStoreSetupService(
      TenantDataStoreRepository repository,
      DataStoreFeatures features,
      DataStorePasswordGenerator passwordGenerator,
      EncryptionService encryptionService,
      ExternalAccountProvisioner provisioner,
      PlatformTenantRepository tenantRepository,
      PlatformAuditRecorder auditRecorder,
      DataStoreConnectionTester connectionTester,
      DataStoreResolver resolver,
      TransactionTemplate transactionTemplate) {
    this.repository = repository;
    this.features = features;
    this.passwordGenerator = passwordGenerator;
    this.encryptionService = encryptionService;
    this.provisioner = provisioner;
    this.tenantRepository = tenantRepository;
    this.auditRecorder = auditRecorder;
    this.connectionTester = connectionTester;
    this.resolver = resolver;
    this.writeTx = transactionTemplate;
    this.readOnlyTx = new TransactionTemplate(transactionTemplate.getTransactionManager());
    this.readOnlyTx.setReadOnly(true);
  }

  /**
   * 요청을 검증한다. 어떤 부수 효과(DB 쓰기·외부 접속)도 일으키지 않으므로 테넌트 행을 만들기 전에 불러도 안전하다.
   *
   * <p>여기서는 백엔드 사용 가능 여부와 백엔드·모드에 따라 달라지는 <b>필수 항목 존재</b>만 본다. 값의 형식(host·database·tablespace 정규식,
   * port 범위)은 DTO 의 Bean Validation 이 컨트롤러에서 거른다. backend null 검사는 Bean Validation 을 거치지 않는 직접 호출에서
   * {@code isAvailable(null)} 로 흘러가지 않게 남겨 둔다.
   *
   * @throws CodedApiException 사용할 수 없는 백엔드(400, {@code DATA_STORE_BACKEND_UNAVAILABLE})
   * @throws IllegalArgumentException 외부 백엔드의 필수 항목 누락(400)
   */
  public void validate(DataStoreSetupRequest request) {
    if (request == null) {
      return;
    }
    if (request.backend() == null) {
      throw new IllegalArgumentException("backend 는 필수입니다");
    }
    if (request.backend() == DataStoreBackend.POSTGRESQL) {
      return;
    }
    if (!features.isAvailable(request.backend())) {
      // 플래그가 꺼진 백엔드나 MYSQL 은 고르지 못하게 막는다(R1).
      throw new CodedApiException(
          HttpStatus.BAD_REQUEST,
          "DATA_STORE_BACKEND_UNAVAILABLE",
          "사용할 수 없는 저장소 백엔드입니다: " + request.backend());
    }
    require(request.host() != null && !request.host().isBlank(), "host");
    require(request.port() != null, "port");
    require(request.database() != null && !request.database().isBlank(), "database");
    require(request.mode() != null, "mode");
    if (request.mode() == DataStoreProvisionMode.DBA_AUTO) {
      require(request.dba() != null, "dba");
    }
  }

  private static void require(boolean ok, String field) {
    if (!ok) {
      throw new IllegalArgumentException("저장소 설정에 " + field + " 값이 필요합니다");
    }
  }

  /**
   * 외부 저장소를 설정한다: 계정 계획을 만들어 비밀번호를 암호화해 저장하고, DBA_AUTO 면 고객 DB 에 계정을 만든다. 요청이 null 이거나 PostgreSQL
   * 이면 아무것도 하지 않고 {@link SetupOutcome#none()} 을 돌려준다.
   *
   * <p><b>호출자는 먼저 {@link #validate} 를 불러야 한다</b>(테넌트 행을 만들기 전에 거르기 위해 {@code
   * PlatformTenantService#create} 가 그렇게 한다). 여기서는 다시 검증하지 않는다.
   *
   * <p>DBA_AUTO 는 계정 생성이 이어서 성공해야 하므로 READY, SCRIPT 는 운영자가 스크립트를 실행하기 전이라 PENDING 으로 저장한다.
   *
   * <p>DBA_AUTO 는 생성이 성공하면 트랜잭션 롤백 시 만든 계정을 지우는 보상을 등록한다. 그래서 이 호출 <b>뒤</b>의 어떤 실패(감사·롤 생성·커밋)도 외부
   * 계정을 남기지 않는다. 생성 도중 실패는 실행기가 이미 보상하고 예외를 던지므로 아무것도 등록하지 않고, 예외 전파로 저장소 행도 롤백된다.
   *
   * <p>DBA_AUTO 의 외부 접속(최대 25문장)은 테넌트 생성 트랜잭션 안에서 일어난다 — 운영자 전용 경로이고 보상 등록에 트랜잭션이 필요해 받아들인다.
   *
   * @return 감사 details(외부일 때 백엔드·호스트·모드, 비밀 없음)와 SCRIPT 이면 스크립트(비밀번호 포함, 응답 1회 노출)
   */
  public SetupOutcome setUp(long tenantId, DataStoreSetupRequest request) {
    if (request == null || request.backend() == DataStoreBackend.POSTGRESQL) {
      return SetupOutcome.none();
    }
    TiberoAccountPlan plan =
        TiberoAccountPlan.generate(tenantId, request.tablespace(), passwordGenerator);
    TenantDataStoreStatus status =
        request.mode() == DataStoreProvisionMode.DBA_AUTO
            ? TenantDataStoreStatus.READY
            : TenantDataStoreStatus.PENDING;
    TenantDataStoreRow row =
        new TenantDataStoreRow(
            tenantId,
            request.backend(),
            request.host(),
            request.port(),
            request.database(),
            plan.schemaName(),
            plan.tablespace(),
            request.mode(),
            status);
    repository.insert(row, toStoredAccounts(plan));
    invalidateResolverAfterCommit(tenantId);

    // 감사에는 백엔드·호스트·모드만 싣는다(비밀 제외).
    Map<String, Object> auditDetails = new LinkedHashMap<>();
    auditDetails.put("dataStoreBackend", request.backend().name());
    auditDetails.put("dataStoreHost", request.host());
    auditDetails.put("dataStoreMode", request.mode().name());

    if (request.mode() == DataStoreProvisionMode.SCRIPT) {
      // 외부 접속 없이 스크립트만 돌려준다 — DBA 가 직접 실행한다.
      return new SetupOutcome(auditDetails, Optional.of(plan.script()));
    }
    ExternalDbTarget target =
        new ExternalDbTarget(request.backend(), request.host(), request.port(), request.database());
    DbaCredentials dba = request.dba();
    List<String> created = provisioner.provision(target, dba.username(), dba.password(), plan);
    registerRollbackCompensation(
        new RollbackCompensation(
            provisioner, target, dba.username(), dba.password(), plan, List.copyOf(created)));
    return new SetupOutcome(auditDetails, Optional.empty());
  }

  /**
   * 커밋 뒤 리졸버 캐시에서 이 테넌트를 지운다.
   *
   * <p>리졸버는 "행 없음 = PostgreSQL" 도 영구 캐시하므로, 생성 트랜잭션이 커밋되기 전에 누군가 이 테넌트의 백엔드를 해석했다면 잘못된 값이 남는다. 커밋
   * 뒤에 지우면 그런 값이 있어도 다음 조회가 행을 다시 읽는다 — 이것이 안전망이고, 호출 순서(행을 먼저 넣기)는 좋은 습관일 뿐이다. 동기화가 없으면(트랜잭션 밖 호출)
   * 행은 이미 반영됐으므로 바로 지운다.
   */
  private void invalidateResolverAfterCommit(long tenantId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      resolver.invalidate(tenantId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            resolver.invalidate(tenantId);
          }
        });
  }

  /** 계획의 계정을 저장용으로 바꾼다. 비밀번호는 암호화한 값만 담는다. */
  private List<StoredAccount> toStoredAccounts(TiberoAccountPlan plan) {
    return plan.accounts().stream()
        .map(
            a -> new StoredAccount(a.role(), a.username(), encryptionService.encrypt(a.password())))
        .toList();
  }

  /**
   * 트랜잭션이 롤백되면 고객 DB 에 만든 계정을 지우도록 동기화를 등록한다.
   *
   * <p>DBA 자격은 보상 객체의 메모리에만 있고 어디에도 저장하지 않는다. 동기화가 비활성이면(트랜잭션 밖 호출) 등록할 수 없으므로 WARN 만 남기고 던지지 않는다 —
   * 이미 만든 계정을 되돌리려다 요청을 실패시키면 안 된다.
   */
  private static void registerRollbackCompensation(RollbackCompensation compensation) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      log.warn("트랜잭션 동기화가 없어 외부 저장소 계정 롤백 보상을 등록하지 못했습니다: {}", compensation.usernames);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(compensation);
  }

  /**
   * 테넌트 생성 롤백 시 이번에 만든 외부 계정을 지우는 보상.
   *
   * <p>서비스 자신을 붙잡지 않도록 정적 클래스로 두고 보상에 필요한 것(실행기·접속 대상·DBA 자격·계획·만든 계정 이름)만 든다. 트랜잭션이 끝날 때 한 번만
   * 실행되며, 실행 뒤에는 참조(특히 DBA 비밀번호)를 비워 메모리에 오래 남지 않게 한다.
   *
   * <p>로그는 바깥 클래스의 로거로 남긴다 — 운영 로그 필터·테스트가 {@code TenantDataStoreSetupService} 이름으로 찾는다.
   */
  private static final class RollbackCompensation implements TransactionSynchronization {

    private ExternalAccountProvisioner provisioner;
    private ExternalDbTarget target;
    private String dbaUsername;
    private String dbaPassword;
    private TiberoAccountPlan plan;
    private List<String> usernames;

    RollbackCompensation(
        ExternalAccountProvisioner provisioner,
        ExternalDbTarget target,
        String dbaUsername,
        String dbaPassword,
        TiberoAccountPlan plan,
        List<String> usernames) {
      this.provisioner = provisioner;
      this.target = target;
      this.dbaUsername = dbaUsername;
      this.dbaPassword = dbaPassword;
      this.plan = plan;
      this.usernames = usernames;
    }

    @Override
    public void afterCompletion(int status) {
      if (usernames == null) {
        // 이미 실행돼 참조를 비웠다.
        return;
      }
      try {
        if (status == STATUS_UNKNOWN) {
          // 커밋·롤백 어느 쪽인지 알 수 없다 — 외부 계정이 고아로 남았을 수 있으니 운영자가 수동 확인하도록
          // 계정 이름만 ERROR 로 남긴다(비밀번호 금지). 자동으로 지우면 커밋된 경우 정상 계정을 날리므로 지우지 않는다.
          log.error("테넌트 생성 트랜잭션 결과를 알 수 없음 — 외부 저장소 고아 계정 가능성, 수동 확인 필요: {}", usernames);
          return;
        }
        if (status != STATUS_ROLLED_BACK) {
          return;
        }
        // 로그에는 계정 이름만 남긴다(비밀번호 금지).
        log.warn("테넌트 생성 롤백 — 외부 저장소 계정 보상 삭제: {}", usernames);
        provisioner.dropAccounts(target, dbaUsername, dbaPassword, plan, usernames);
      } finally {
        provisioner = null;
        target = null;
        dbaUsername = null;
        dbaPassword = null;
        plan = null;
        usernames = null;
      }
    }

    /** 비밀번호·계획을 담고 있어 계정 수만 보여 준다. */
    @Override
    public String toString() {
      return "RollbackCompensation[accounts="
          + (usernames == null ? "cleared" : usernames.size())
          + ", secrets=****]";
    }
  }

  // ── 운영자 API(WD-73 Task 5) ───────────────────────────────────────────────

  /** 선택 가능한 백엔드 목록(enum 선언 순: POSTGRESQL, TIBERO, MYSQL)과 각 사용 가능 여부. */
  public List<DataStoreBackendOption> availableBackends() {
    return Arrays.stream(DataStoreBackend.values())
        .map(backend -> new DataStoreBackendOption(backend, features.isAvailable(backend)))
        .toList();
  }

  /**
   * 생성 전 DBA 연결 테스트(R7). 기능 플래그와 무관하게 동작해 운영자가 도달 가능 여부를 먼저 확인할 수 있다.
   *
   * <p>접속 실패는 예외가 아니라 {@code ok=false} 결과로 돌려준다. JDBC URL 을 만들 수 있는 백엔드는 Tibero 뿐이다.
   *
   * @throws IllegalArgumentException Tibero 가 아닌 백엔드(400)
   */
  public ConnectionTestResult testConnection(DataStoreConnectionTestRequest request) {
    if (request.backend() != DataStoreBackend.TIBERO) {
      throw new IllegalArgumentException("연결 테스트를 지원하지 않는 저장소 백엔드입니다: " + request.backend());
    }
    ExternalDbTarget target =
        new ExternalDbTarget(request.backend(), request.host(), request.port(), request.database());
    DbaCredentials dba = request.credentials();
    return connectionTester.testDba(target, dba.username(), dba.password());
  }

  /** 테넌트의 저장소 설정 조회. 행이 없으면 기본 PostgreSQL. 비밀번호·암호문은 싣지 않는다. */
  @Transactional(readOnly = true)
  public TenantDataStoreResponse detail(long tenantId) {
    requireTenant(tenantId);
    Optional<TenantDataStoreRow> row = repository.find(tenantId);
    if (row.isEmpty()) {
      return new TenantDataStoreResponse(
          DataStoreBackend.POSTGRESQL, null, null, null, null, null, null, null, List.of());
    }
    TenantDataStoreRow r = row.get();
    List<String> usernames =
        repository.findAccounts(tenantId).stream().map(StoredAccount::username).toList();
    return new TenantDataStoreResponse(
        r.backend(),
        r.host(),
        r.port(),
        r.database(),
        r.schemaName(),
        r.tablespace(),
        r.mode(),
        r.status(),
        usernames);
  }

  /**
   * 저장된 계정 전부로 외부 DB 에 접속해 보고, 모두 성공하면 READY 로 바꾼다.
   *
   * <p>이미 READY 인 저장소는 실패해도 PENDING 으로 내리지 않는다(내리는 판단은 WD-74 운영).
   *
   * <p><b>트랜잭션을 셋으로 나눈다.</b> 외부 접속은 계정당 최대 10초씩 12개(동시 4개씩이라도 수십 초)라 그동안 DB 커넥션을 붙잡지 않도록 (1) 짧은 읽기
   * 전용 트랜잭션으로 저장소·계정을 읽고, (2) 트랜잭션 없이 접속을 시험한 뒤, (3) 짧은 쓰기 트랜잭션에서 상태 전환과 감사를 한 번에 한다.
   *
   * <p><b>재발급 경합.</b> (1)과 (3) 사이에 {@link #reissueScript} 가 비밀번호를 바꾸면, 이전 비밀번호로 확인한 결과로 READY 를
   * 확정하면 안 된다(DBA 가 아직 새 스크립트를 실행하지 않았을 수 있다). 그래서 (3)에서 저장소 행을 잠그고(재발급도 같은 행을 잠근다) 계정을 다시 읽어 (1)의
   * 암호문과 다르면 전환하지 않고 PENDING 인 채로 결과만 돌려준다. 감사 {@code result} 는 이때 {@code PASSWORDS_CHANGED} 다.
   */
  public DataStoreVerifyResponse verify(long tenantId, long operatorId) {
    VerifySnapshot snapshot =
        readOnlyTx.execute(
            s ->
                new VerifySnapshot(
                    requireExternalStore(tenantId), repository.findAccounts(tenantId)));
    TenantDataStoreRow row = snapshot.row();
    List<PlannedAccount> planned =
        snapshot.accounts().stream()
            .map(
                a ->
                    new PlannedAccount(
                        a.role(), a.username(), encryptionService.decrypt(a.encryptedPassword())))
            .toList();
    ExternalDbTarget target =
        new ExternalDbTarget(row.backend(), row.host(), row.port(), row.database());
    // 트랜잭션 밖: 외부 접속 동안 DB 커넥션을 쥐지 않는다.
    List<AccountCheck> checks = connectionTester.verifyAccounts(target, row.schemaName(), planned);
    List<String> failed = checks.stream().filter(c -> !c.ok()).map(AccountCheck::username).toList();
    return writeTx.execute(
        s -> {
          TenantDataStoreStatus status = row.status();
          String result = failed.isEmpty() ? "READY" : "FAILED";
          if (failed.isEmpty() && status != TenantDataStoreStatus.READY) {
            // 재발급과 직렬화한 뒤, 확인에 쓴 암호문이 지금도 그대로일 때만 READY 로 바꾼다.
            repository.lockForUpdate(tenantId);
            if (repository.findAccounts(tenantId).equals(snapshot.accounts())) {
              repository.updateStatus(tenantId, TenantDataStoreStatus.READY);
              status = TenantDataStoreStatus.READY;
            } else {
              result = "PASSWORDS_CHANGED";
              log.warn("검증 도중 저장소 비밀번호가 재발급되어 READY 로 바꾸지 않음: tenant={}", tenantId);
            }
          }
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("result", result);
          details.put("failedAccounts", failed);
          auditRecorder.record(
              operatorId,
              "TENANT_DATA_STORE_VERIFY",
              "tenant",
              String.valueOf(tenantId),
              "테넌트 데이터셋 저장소 계정 검증",
              details);
          return new DataStoreVerifyResponse(status, result, checks);
        });
  }

  /** {@link #verify} 가 읽기 트랜잭션에서 가져온 저장소 행과 계정(암호문). 평문 비밀번호는 담지 않는다. */
  private record VerifySnapshot(TenantDataStoreRow row, List<StoredAccount> accounts) {}

  /**
   * 스크립트 모드이고 아직 PENDING 인 저장소의 모든 비밀번호를 새로 만들어 교체하고 새 스크립트를 돌려준다(R2).
   *
   * <p>이전 스크립트는 더 이상 유효하지 않다. 감사 details 에는 비밀을 넣지 않는다.
   *
   * @throws CodedApiException PENDING·SCRIPT 가 아니면 409 {@code DATA_STORE_NOT_PENDING}
   */
  @Transactional
  public DataStoreScriptResponse reissueScript(long tenantId, long operatorId) {
    // verify 의 READY 전환과 직렬화한다 — 잠근 뒤 읽어야 방금 READY 가 된 저장소를 PENDING 으로 오인해 재발급하지 않는다.
    repository.lockForUpdate(tenantId);
    TenantDataStoreRow row = requireExternalStore(tenantId);
    if (row.mode() != DataStoreProvisionMode.SCRIPT
        || row.status() != TenantDataStoreStatus.PENDING) {
      throw new CodedApiException(
          HttpStatus.CONFLICT,
          "DATA_STORE_NOT_PENDING",
          "스크립트 재발급은 PENDING 상태의 SCRIPT 모드 저장소에서만 할 수 있습니다");
    }
    TiberoAccountPlan plan =
        TiberoAccountPlan.generate(tenantId, row.tablespace(), passwordGenerator);
    repository.replacePasswords(tenantId, toStoredAccounts(plan));
    auditRecorder.record(
        operatorId,
        "TENANT_DATA_STORE_SCRIPT_REISSUE",
        "tenant",
        String.valueOf(tenantId),
        "테넌트 데이터셋 저장소 스크립트 재발급",
        Map.of("dataStoreBackend", row.backend().name()));
    return new DataStoreScriptResponse(plan.script());
  }

  private void requireTenant(long tenantId) {
    if (!tenantRepository.existsById(tenantId)) {
      throw new TenantNotFoundException("테넌트를 찾을 수 없습니다: " + tenantId);
    }
  }

  /** 테넌트가 있고 외부 저장소 행을 가진 경우만 통과한다. */
  private TenantDataStoreRow requireExternalStore(long tenantId) {
    requireTenant(tenantId);
    return repository
        .find(tenantId)
        .orElseThrow(() -> new IllegalArgumentException("외부 데이터셋 저장소를 쓰지 않는 테넌트입니다"));
  }

  /**
   * {@link #setUp} 의 결과: 생성 감사에 병합할 details 와 SCRIPT 모드의 1회용 스크립트.
   *
   * <p>스크립트에 비밀번호가 들어 있어 {@code toString()} 은 스크립트 유무만 보여 준다. details 는 바꿀 수 없는 사본으로 둔다.
   */
  public record SetupOutcome(Map<String, Object> auditDetails, Optional<String> setupScript) {

    private static final SetupOutcome NONE = new SetupOutcome(Map.of(), Optional.empty());

    public SetupOutcome {
      auditDetails = Collections.unmodifiableMap(new LinkedHashMap<>(auditDetails));
    }

    /** 외부 저장소가 없는(기본 PostgreSQL) 경우: 감사 details 없음, 스크립트 없음. */
    public static SetupOutcome none() {
      return NONE;
    }

    @Override
    public String toString() {
      return "SetupOutcome[auditDetails="
          + auditDetails
          + ", setupScript="
          + (setupScript.isPresent() ? "****" : "none")
          + "]";
    }
  }
}
