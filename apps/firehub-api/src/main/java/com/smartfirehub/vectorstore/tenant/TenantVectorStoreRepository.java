package com.smartfirehub.vectorstore.tenant;

import java.util.List;
import java.util.Optional;

/**
 * 테넌트 전용 Qdrant 설정({@code tenant_vector_store}, WD-742) 접근. 전역 테이블(RLS 없음)이라 테넌트 컨텍스트 없이 읽고 쓰며, 모든
 * 문장이 tenant id 를 술어로 직접 건다. 트랜잭션 경계는 호출 서비스가 잡는다(테넌트 생성과 같은 트랜잭션에서 insert).
 *
 * <p>구현: PG 플랫폼은 jOOQ, Tibero 플랫폼은 MyBatis(PlatformRepositoryPairTest 짝).
 */
public interface TenantVectorStoreRepository {

  /** 전용 Qdrant 설정을 저장한다(테넌트당 한 행 — 이미 있으면 키 위반). */
  void insert(TenantVectorStore row);

  /** 테넌트의 전용 Qdrant 설정. 없으면 empty(= 공유 Qdrant). */
  Optional<TenantVectorStore> find(long tenantId);

  /** 전용 Qdrant 를 쓰는 테넌트 id 들(오름차순) — 배경 작업이 인스턴스별 상태를 미리 볼 때 쓴다. */
  List<Long> findTenantIds();
}
