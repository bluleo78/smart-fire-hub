package com.smartfirehub.dataset.storage.tibero;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStorePasswordGenerator;
import com.smartfirehub.dataset.storage.DataStoreProvisionMode;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.dataset.storage.external.ExternalAccountProvisioner;
import com.smartfirehub.dataset.storage.external.ExternalDbTarget;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tibero 실서버 테스트의 공용 환경(WD-74).
 *
 * <p>환경변수({@code TIBERO_TEST_HOST/PORT/SID/DBA_USER/DBA_PASSWORD}, 선택 {@code
 * TIBERO_TEST_TABLESPACE})에서 접속 정보를 읽고, 계약 테스트용 전용 테넌트의 Tibero 계정 12개를 만들고 지운다. 값은 어디에도 출력하지 않는다.
 *
 * <p><b>왜 전용 테넌트인가.</b> 공유 테스트 DB 의 기존 테넌트를 건드리지 않고, 새 id 로 {@code GENIA_T{id}} 계정을 만들어 충돌을 피한다.
 * 정리는 항상 이 테스트가 만든 계정·테넌트로만 한정한다.
 */
final class TiberoTestEnvironment {

  /** 드라이버가 없을 때 건너뛰는 사유. */
  static final String NO_DRIVER_MESSAGE =
      "Tibero JDBC 드라이버 없음 — apps/firehub-api/libs/ 에 jar 를 두세요";

  private TiberoTestEnvironment() {}

  /** Tibero JDBC 드라이버가 클래스패스에 있으면 true. */
  static boolean driverPresent() {
    try {
      Class.forName(TiberoDialect.DRIVER_CLASS);
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /** 드라이버가 없으면 테스트를 건너뛴다(환경변수만 설정되고 jar 가 없을 때 훅이 초록을 유지하도록). */
  static void assumeDriverPresent() {
    assumeTrue(driverPresent(), NO_DRIVER_MESSAGE);
  }

  private static String env(String name, String fallback) {
    String v = System.getenv(name);
    return (v == null || v.isBlank()) ? fallback : v;
  }

  /** 환경변수에서 읽은 접속 정보. 비밀번호가 있어 toString 은 가린다. */
  record Dba(ExternalDbTarget target, String user, String password) {
    @Override
    public String toString() {
      return "Dba[" + target.host() + ":" + target.port() + "/" + target.database() + "]";
    }
  }

  /** 환경변수에서 DBA 접속 정보를 만든다. 필수 값이 비어 있으면 IllegalStateException. */
  static Dba dbaFromEnv() {
    String host = env("TIBERO_TEST_HOST", null);
    String user = env("TIBERO_TEST_DBA_USER", null);
    String password = env("TIBERO_TEST_DBA_PASSWORD", null);
    if (host == null || user == null || password == null) {
      throw new IllegalStateException(
          "TIBERO_TEST_HOST / TIBERO_TEST_DBA_USER / TIBERO_TEST_DBA_PASSWORD 가 필요합니다");
    }
    int port = Integer.parseInt(env("TIBERO_TEST_PORT", "8629"));
    String sid = env("TIBERO_TEST_SID", "tibero");
    return new Dba(new ExternalDbTarget(DataStoreBackend.TIBERO, host, port, sid), user, password);
  }

  /** DBA 로 직접 JDBC 연결을 연다(방언 확인용). 호출자가 닫는다. */
  static Connection openDba(Dba dba) throws SQLException {
    return DriverManager.getConnection(dba.target().jdbcUrl(), dba.user(), dba.password());
  }

  /** 준비된 테스트 테넌트와 그 Tibero 계정. 정리에 필요한 협력 객체를 함께 들고 있다. */
  static final class Provisioned {
    final long tenantId;
    private final Dba dba;
    private final TiberoAccountPlan plan;
    private final ExternalAccountProvisioner provisioner;
    private final ExternalStoreConnections connections;
    private final DSLContext dsl;
    private final TransactionTemplate tx;

    private Provisioned(
        long tenantId,
        Dba dba,
        TiberoAccountPlan plan,
        ExternalAccountProvisioner provisioner,
        ExternalStoreConnections connections,
        DSLContext dsl,
        TransactionTemplate tx) {
      this.tenantId = tenantId;
      this.dba = dba;
      this.plan = plan;
      this.provisioner = provisioner;
      this.connections = connections;
      this.dsl = dsl;
      this.tx = tx;
    }

    /**
     * 정리: 풀 닫기 → 계정 12개 DROP → 테넌트(저장소 행 포함) 삭제. 앞 단계가 실패해도 뒤 단계는 반드시 실행한다.
     *
     * <p>풀을 먼저 닫는 이유는 접속 중인 세션이 있으면 {@code DROP USER} 가 거절되기 때문이다.
     */
    void close() {
      close(true);
    }

    /**
     * @param dropAccounts false 면 Tibero 계정은 건드리지 않고 테넌트만 지운다 — 프로비저닝이 "이미 있는 계정" 으로 거절된 경우 그 계정은
     *     우리가 만든 것이 아니므로 절대 지우면 안 된다.
     */
    void close(boolean dropAccounts) {
      try {
        try {
          connections.closeAll();
        } finally {
          if (dropAccounts) {
            provisioner.dropAccounts(
                dba.target(),
                dba.user(),
                dba.password(),
                plan,
                plan.accounts().stream().map(PlannedAccount::username).toList().reversed());
          }
        }
      } finally {
        // 계정 삭제를 시도한 뒤에만(또는 지우지 않기로 한 경우) 테넌트 행을 지운다.
        TenantRlsTestSupport.deleteProvisionedTenantCascade(dsl, tx, tenantId);
      }
    }
  }

  /**
   * 전용 테넌트를 만들고 Tibero 계정 12개를 프로비저닝해 READY 행을 넣는다.
   *
   * <p>순서: 풀 비우기(다른 테스트의 풀이 섞이지 않게) → 예약 대역 id 로 테넌트 생성 → 계정 생성(이미 있는 계정이 있으면 이름을 담아 거절 — 기존 계정은 절대
   * 지우지 않는다) → 암호화한 비밀번호와 함께 READY 행 저장. 계정 생성 뒤 실패하면 우리가 만든 것만 되돌리고 예외를 던진다.
   */
  static Provisioned provision(
      DSLContext dsl,
      TransactionTemplate tx,
      TenantDataStoreRepository storeRepository,
      ExternalAccountProvisioner provisioner,
      ExternalStoreConnections connections,
      EncryptionService encryption,
      DataStorePasswordGenerator passwordGenerator) {
    Dba dba = dbaFromEnv();
    // 다른 테스트가 남긴 풀이 이 테넌트 키와 섞이지 않게 먼저 비운다.
    connections.closeAll();

    // 로컬 PG 시퀀스 id 는 DB 초기화 뒤 공유 Tibero 의 실제 테넌트 id 와 겹칠 수 있다. 예약 대역(900,000,000 + 무작위)의 id 를
    // 명시해 충돌 확률을 낮춘다. 그래도 겹치면 프로비저너가 거절하므로 남의 계정은 지워지지 않는다.
    long tenantId = 900_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L);
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantId);
    TiberoAccountPlan plan =
        TiberoAccountPlan.generate(
            tenantId, System.getenv("TIBERO_TEST_TABLESPACE"), passwordGenerator);
    Provisioned provisioned =
        new Provisioned(tenantId, dba, plan, provisioner, connections, dsl, tx);
    // 같은 이름 계정이 이미 있으면 프로비저너가 계정 이름을 담아 거절한다. 그 계정은 우리가 만든 것이 아니므로 절대 지우지 않는다.
    // 프로비저너는 자기가 만든 계정만 스스로 되돌리므로, 여기서는 테넌트만 정리하고 계정(dropAccounts)은 건드리지 않는다.
    try {
      provisioner.provision(dba.target(), dba.user(), dba.password(), plan);
    } catch (RuntimeException refused) {
      try {
        provisioned.close(false);
      } catch (RuntimeException suppressed) {
        refused.addSuppressed(suppressed);
      }
      throw new IllegalStateException(
          "테스트 테넌트 "
              + tenantId
              + " 의 Tibero 계정을 만들지 못했습니다. 이미 있는 계정이 있으면 이름이 아래에 나옵니다 — "
              + "이전 테스트의 잔재임을 확인한 뒤에만 수동으로 DROP 하세요: "
              + refused.getMessage(),
          refused);
    }

    // 여기부터는 이 테스트가 만든 계정이므로 실패하면 계정까지 지운다.
    try {
      List<StoredAccount> accounts = new ArrayList<>();
      for (PlannedAccount a : plan.accounts()) {
        accounts.add(new StoredAccount(a.role(), a.username(), encryption.encrypt(a.password())));
      }
      TenantDataStoreRow row =
          new TenantDataStoreRow(
              tenantId,
              DataStoreBackend.TIBERO,
              dba.target().host(),
              dba.target().port(),
              dba.target().database(),
              plan.schemaName(),
              plan.tablespace(),
              DataStoreProvisionMode.DBA_AUTO,
              TenantDataStoreStatus.READY);
      tx.executeWithoutResult(s -> storeRepository.insert(row, accounts));
      return provisioned;
    } catch (RuntimeException e) {
      try {
        provisioned.close();
      } catch (RuntimeException suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
  }
}
