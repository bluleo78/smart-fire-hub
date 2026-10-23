package com.smartfirehub.dataset.storage.executor;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.global.exception.CodedApiException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 테넌트의 저장소 기술자를 만든다(WD-75).
 *
 * <p>백엔드 판정의 단일 출처다 — executor 는 요청 페이로드가 아니라 이 결과만 믿는다. 행이 없으면 기본 PostgreSQL 이다. Tibero 는 READY 일
 * 때만 OWNER 계정 하나를 복호화해 낸다(RO 는 {@code includeReadOnly} 요청 시에만, 슬롯은 소비자가 없으므로 복호화하지 않아 평문 노출을
 * 최소화한다).
 */
@Slf4j
@Service
public class ExecutorDataStoreDescriptorService {

  private final TenantDataStoreRepository repository;
  private final EncryptionService encryption;

  public ExecutorDataStoreDescriptorService(
      TenantDataStoreRepository repository, EncryptionService encryption) {
    this.repository = repository;
    this.encryption = encryption;
  }

  /**
   * @param includeReadOnly true 면 Tibero 기술자에 읽기 전용(RO) 계정을 복호화해 싣는다(db-executor 분석 조회용, WD-76). RO
   *     행이 없으면 {@code ro} 없이 응답한다(실패 안내는 소비자 몫). PostgreSQL 테넌트는 이 값과 무관하게 PG 기술자 그대로다.
   * @param secretsAllowed 평문 비밀번호를 내도 되는가(내부 토큰이 안전하게 설정됨). false 면 비밀번호가 담길 TIBERO 기술자는 복호화 전에 503
   *     으로 거부하고, 비밀이 없는 POSTGRESQL 기술자는 그대로 낸다.
   * @throws CodedApiException 503 — 비밀번호를 낼 수 없는 토큰 설정(EXECUTOR_TOKEN_NOT_CONFIGURED), 409 — Tibero
   *     가 아직 READY 가 아니거나(DATA_STORE_NOT_READY) 지원하지 않는 백엔드(DATA_STORE_BACKEND_UNSUPPORTED)
   */
  public ExecutorDataStoreDescriptor describe(
      long tenantId, boolean secretsAllowed, boolean includeReadOnly) {
    TenantDataStoreRow row = repository.find(tenantId).orElse(null);
    if (row == null || row.backend() == DataStoreBackend.POSTGRESQL) {
      return ExecutorDataStoreDescriptor.postgres(tenantId);
    }
    if (row.backend() != DataStoreBackend.TIBERO) {
      throw new CodedApiException(
          HttpStatus.CONFLICT, "DATA_STORE_BACKEND_UNSUPPORTED", "MySQL 저장소는 아직 실행기에서 지원하지 않습니다");
    }
    if (row.status() != TenantDataStoreStatus.READY) {
      throw new CodedApiException(
          HttpStatus.CONFLICT, "DATA_STORE_NOT_READY", "데이터셋 저장소 계정 확인이 끝나지 않았습니다");
    }
    // 백엔드가 TIBERO 로 확정된 뒤, 복호화 전에 거부한다 — 기본 토큰 환경에서도 PG 테넌트 조회는 계속 돼야 하기 때문이다.
    if (!secretsAllowed) {
      throw new CodedApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          ExecutorDataStoreController.TOKEN_NOT_CONFIGURED_CODE,
          "executor 내부 토큰이 설정되지 않았거나 기본값이라 저장소 접속 정보를 제공할 수 없습니다");
    }
    // READY 인데 소유 계정 행이 없는 것은 데이터 불일치 — 코드 없는 오류 대신 NOT_READY 로 알린다.
    List<StoredAccount> accounts = repository.findAccounts(tenantId);
    StoredAccount owner =
        accounts.stream()
            .filter(a -> a.role() == DataStoreAccountRole.OWNER)
            .findFirst()
            .orElseThrow(
                () ->
                    new CodedApiException(
                        HttpStatus.CONFLICT,
                        "DATA_STORE_NOT_READY",
                        "데이터셋 저장소 소유 계정 정보를 찾지 못했습니다"));
    // RO 는 요청 시에만 복호화한다 — 쓰지 않는 호출자에게 평문을 만들지 않기 위해서다.
    ExecutorDataStoreDescriptor.Account ro =
        includeReadOnly
            ? accounts.stream()
                .filter(a -> a.role() == DataStoreAccountRole.RO)
                .findFirst()
                .map(a -> decryptReadOnly(tenantId, a))
                .orElse(null)
            : null;
    return new ExecutorDataStoreDescriptor(
        tenantId,
        DataStoreBackend.TIBERO,
        row.host(),
        row.port(),
        row.database(),
        row.schemaName(),
        new ExecutorDataStoreDescriptor.Account(
            owner.username(), encryption.decrypt(owner.encryptedPassword())),
        ro);
  }

  /**
   * RO 계정을 복호화한다. 실패하면(손상·키 교체된 암호문) 경고만 남기고 null — 기술자에서 {@code ro} 를 뺀다.
   *
   * <p>왜 삼키는가: db-executor 는 OWNER 만 쓰는 파이프라인 경로까지 모든 기술자를 {@code ?include=ro} 로 받는다. RO 복호화 실패로
   * describe 가 던지면 분석 조회와 무관한 파이프라인까지 멈춘다. {@code ro} 가 빠지면 분석 조회만 「Tibero 읽기 전용 계정이 준비되지 않았습니다.」로
   * 실패한다. OWNER 복호화 실패는 그대로 던진다(실행 자체가 불가능하다).
   *
   * <p>로그에는 테넌트·예외 종류만 남긴다 — 예외 메시지·원인에 암호문이나 키 정보가 섞이지 않게 예외 객체를 넘기지 않는다.
   */
  private ExecutorDataStoreDescriptor.Account decryptReadOnly(long tenantId, StoredAccount a) {
    try {
      return new ExecutorDataStoreDescriptor.Account(
          a.username(), encryption.decrypt(a.encryptedPassword()));
    } catch (RuntimeException e) {
      log.warn(
          "Tibero 읽기 전용 계정 비밀번호 복호화 실패 — 기술자에서 ro 를 뺀다 tenant={} 종류={}",
          tenantId,
          e.getClass().getName());
      return null;
    }
  }
}
