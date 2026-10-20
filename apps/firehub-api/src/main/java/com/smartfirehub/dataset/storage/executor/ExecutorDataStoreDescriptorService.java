package com.smartfirehub.dataset.storage.executor;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.global.exception.CodedApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 테넌트의 저장소 기술자를 만든다(WD-75).
 *
 * <p>백엔드 판정의 단일 출처다 — executor 는 요청 페이로드가 아니라 이 결과만 믿는다. 행이 없으면 기본 PostgreSQL 이다. Tibero 는 READY 일
 * 때만 OWNER 계정 하나를 복호화해 낸다(RO·슬롯은 executor 소비자가 없으므로 복호화조차 하지 않아 평문 노출을 최소화한다).
 */
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
   * @param secretsAllowed 평문 비밀번호를 내도 되는가(내부 토큰이 안전하게 설정됨). false 면 비밀번호가 담길 TIBERO 기술자는 복호화 전에 503
   *     으로 거부하고, 비밀이 없는 POSTGRESQL 기술자는 그대로 낸다.
   * @throws CodedApiException 503 — 비밀번호를 낼 수 없는 토큰 설정(EXECUTOR_TOKEN_NOT_CONFIGURED), 409 — Tibero
   *     가 아직 READY 가 아니거나(DATA_STORE_NOT_READY) 지원하지 않는 백엔드(DATA_STORE_BACKEND_UNSUPPORTED)
   */
  public ExecutorDataStoreDescriptor describe(long tenantId, boolean secretsAllowed) {
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
    StoredAccount owner =
        repository.findAccounts(tenantId).stream()
            .filter(a -> a.role() == DataStoreAccountRole.OWNER)
            .findFirst()
            .orElseThrow(
                () ->
                    new CodedApiException(
                        HttpStatus.CONFLICT,
                        "DATA_STORE_NOT_READY",
                        "데이터셋 저장소 소유 계정 정보를 찾지 못했습니다"));
    return new ExecutorDataStoreDescriptor(
        tenantId,
        DataStoreBackend.TIBERO,
        row.host(),
        row.port(),
        row.database(),
        row.schemaName(),
        new ExecutorDataStoreDescriptor.Account(
            owner.username(), encryption.decrypt(owner.encryptedPassword())));
  }
}
