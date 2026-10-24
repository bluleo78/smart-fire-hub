package com.smartfirehub.securitylevel.pythonread;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.Clearance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

  public PythonReadGrants(
      DataStoreResolver resolver, PythonReadGrantSync pg, TiberoPythonReadGrantSync tibero) {
    this.resolver = resolver;
    this.pg = pg;
    this.tibero = tibero;
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

  /** 데이터셋 하나의 테이블만 재동기화. */
  public void syncDataset(long datasetId) {
    delegate().syncDataset(datasetId);
  }

  /** 쓰기 경로 훅 — 커밋 후(트랜잭션이 없으면 즉시) 그 테이블만 재동기화. 실패는 로그만. */
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
