package com.smartfirehub.platform.service;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.platform.dto.TenantVectorStoreResponse;
import com.smartfirehub.platform.dto.VectorStoreSetupRequest;
import com.smartfirehub.vectorstore.tenant.DedicatedVectorStoreProbe;
import com.smartfirehub.vectorstore.tenant.TenantVectorStore;
import com.smartfirehub.vectorstore.tenant.TenantVectorStoreRepository;
import com.smartfirehub.vectorstore.tenant.TenantVectorStoreTargets;
import com.smartfirehub.vectorstore.tenant.VectorStoreConnectionTestResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 테넌트 전용 벡터 저장소(Qdrant) 설정(WD-742). 테넌트를 만들 때만 정한다 — 공유↔전용 전환·컬렉션 옮기기는 하지 않는다(전용 데이터 저장소와 같은 원칙).
 *
 * <p>전용 인스턴스는 벡터 저장소가 Qdrant 인 설치본에서만 쓸 수 있다 — 연결 확인 빈({@link DedicatedVectorStoreProbe})이 그 조합에만
 * 있다. pgvector·꺼짐 설치본에서 입력이 오면 테넌트를 만들기 전에 거절한다.
 */
@Service
public class TenantVectorStoreSetupService {

  private final TenantVectorStoreRepository repository;
  private final EncryptionService encryption;
  private final ObjectProvider<DedicatedVectorStoreProbe> probe;
  private final ObjectProvider<TenantVectorStoreTargets> targets;

  public TenantVectorStoreSetupService(
      TenantVectorStoreRepository repository,
      EncryptionService encryption,
      ObjectProvider<DedicatedVectorStoreProbe> probe,
      ObjectProvider<TenantVectorStoreTargets> targets) {
    this.repository = repository;
    this.encryption = encryption;
    this.probe = probe;
    this.targets = targets;
  }

  /** 이 설치본에서 테넌트 전용 벡터 저장소를 쓸 수 있는가(Qdrant 조합). 화면이 입력칸을 보일지 정한다. */
  public boolean dedicatedSupported() {
    return probe.getIfAvailable() != null;
  }

  /** 입력 검증 — 테넌트 행을 만들기 전에 부른다. 입력이 없으면 할 일 없음(공유). */
  public void validate(VectorStoreSetupRequest request) {
    if (request != null && !dedicatedSupported()) {
      throw new IllegalArgumentException("이 설치본은 벡터 저장소가 Qdrant 가 아니어서 테넌트 전용 벡터 저장소를 쓸 수 없습니다");
    }
  }

  /** 연결 확인(저장하지 않는다). Qdrant 조합이 아니면 거절한다. */
  public VectorStoreConnectionTestResult testConnection(VectorStoreSetupRequest request) {
    DedicatedVectorStoreProbe p = probe.getIfAvailable();
    if (p == null) {
      throw new IllegalArgumentException("이 설치본은 테넌트 전용 벡터 저장소를 쓸 수 없습니다(벡터 저장소가 Qdrant 가 아님)");
    }
    return p.probe(request.host(), request.grpcPort(), request.tls(), request.apiKey());
  }

  /**
   * 설정을 저장한다(테넌트 생성 트랜잭션 안). 입력이 없으면 아무것도 하지 않는다. 커밋 뒤 대상 해석 캐시를 지운다 — 생성 전에 그 테넌트 id 로 해석한 값(공유)이
   * 남지 않게.
   *
   * @return 감사 상세(비밀 제외) — 입력이 없으면 빈 맵
   */
  public Map<String, Object> setUp(long tenantId, VectorStoreSetupRequest request) {
    if (request == null) return Map.of();
    repository.insert(
        new TenantVectorStore(
            tenantId,
            request.host(),
            request.grpcPort(),
            request.tls(),
            encryption.encrypt(request.apiKey())));
    invalidateAfterCommit(tenantId);
    Map<String, Object> audit = new LinkedHashMap<>();
    audit.put("vectorStoreHost", request.host());
    audit.put("vectorStorePort", request.grpcPort());
    return audit;
  }

  /** 테넌트 벡터 저장소 정보(비밀 제외). */
  public TenantVectorStoreResponse detail(long tenantId) {
    return repository
        .find(tenantId)
        .map(r -> new TenantVectorStoreResponse(true, r.host(), r.grpcPort(), r.tls()))
        .orElse(new TenantVectorStoreResponse(false, null, null, null));
  }

  private void invalidateAfterCommit(long tenantId) {
    TenantVectorStoreTargets t = targets.getIfAvailable();
    if (t == null) return;
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      t.invalidate(tenantId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            t.invalidate(tenantId);
          }
        });
  }
}
