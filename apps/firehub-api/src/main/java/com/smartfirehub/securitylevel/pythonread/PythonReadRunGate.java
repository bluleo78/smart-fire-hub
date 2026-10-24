package com.smartfirehub.securitylevel.pythonread;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.securitylevel.access.Clearance;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * PYTHON 실행 직전 준비({@code prepareForRun})의 <b>백엔드 공통 판정</b> — 사전 검사(테넌트 일치·열람 등급·읽기 범위), 슬롯 범위·존재
 * 확인, 실행 슬롯의 회수 실패 거부(fail-closed).
 *
 * <p><b>왜 따로 두는가.</b> PG({@link PythonReadGrantSync})와 Tibero({@link TiberoPythonReadGrantSync})는
 * GRANT 를 맞추는 방법만 다르고, "누가 어느 슬롯으로 실행해도 되는가"와 "어떤 실패에서 실행을 거부하는가"는 같아야 한다. 이 규칙을 두 곳에 복사하면 한쪽만 고쳐져
 * 한 백엔드의 보안 경계가 어긋난다. 동작과 메시지는 PG 에 있던 것 그대로다.
 */
@Slf4j
final class PythonReadRunGate {

  private PythonReadRunGate() {}

  /**
   * 동기화를 돌려 실행 슬롯 번호를 정한다.
   *
   * @param sync 실행 슬롯 롤 하나만 맞추는 동기화(예외는 모두 "준비하지 못함" 으로 거부된다)
   * @param slotRoleName 슬롯 번호 → 그 슬롯 계정/롤 이름(회수 실패가 이 실행 슬롯에 남았는지 가리는 용도)
   * @return 실행에 쓸 슬롯 번호(1~10)
   * @throws PythonReadAccessException 사전 검사·동기화·슬롯 범위·슬롯 미준비·회수 실패 중 하나
   */
  static int prepare(
      Clearance runAs,
      int readRank,
      Supplier<PythonReadGrantSync.SyncResult> sync,
      IntFunction<String> slotRoleName) {
    // 테넌트 일치 확인을 맨 먼저 — 동기화(syncTenant)는 TenantContext 테넌트를, 슬롯 계산은 runAs 의 rank 를 쓴다. 둘이 다르면
    // 남의 테넌트 등급 스냅샷으로 슬롯을 고르게 되므로(과권한 가능) 계산 전에 거부한다. 컨텍스트가 비어도 같은 예외(fail-closed).
    Long contextTenant = TenantContext.get();
    if (contextTenant == null || contextTenant != runAs.tenantId()) {
      log.error(
          "PYTHON 실행 주체 테넌트 불일치: runAs.tenant={} context.tenant={}",
          runAs.tenantId(),
          contextTenant);
      throw new PythonReadAccessException("실행 주체의 테넌트가 현재 실행 테넌트와 달라 Python 스크립트를 실행할 수 없습니다.");
    }
    if (runAs.rank() == Clearance.NO_RANK) {
      throw new PythonReadAccessException("실행 주체에게 열람 등급이 없어 Python 스크립트를 실행할 수 없습니다.");
    }
    if (readRank > runAs.rank()) {
      throw new PythonReadAccessException("Python 읽기 범위가 실행 주체의 열람 등급을 넘어 실행할 수 없습니다.");
    }
    PythonReadGrantSync.SyncResult result;
    try {
      result = sync.get();
    } catch (RuntimeException e) {
      throw new PythonReadAccessException("Python 읽기 권한을 준비하지 못해 실행을 중단했습니다.", e);
    }
    int slot = result.runSlot();
    if (slot < 1 || slot > TenantPipelineRole.PYTHON_READ_SLOTS) {
      throw new PythonReadAccessException("실행 주체의 열람 등급이 Python 읽기 슬롯 범위(1~10)를 벗어났습니다.");
    }
    if (!result.availableSlots().contains(slot)) {
      throw new PythonReadAccessException("Python 읽기 롤이 준비되지 않았습니다. 관리자에게 문의하세요.");
    }
    // 회수 실패 = 그 롤에 과권한이 남았을 수 있다. 거부는 이 실행이 접속할 슬롯 롤에 남은 경우로만 좁힌다 — 다른 슬롯 롤의 과권한은 이 스크립트가
    // 쓸 수 없다(슬롯 롤끼리 멤버십이 없고 직접 로그인한다). 넓게 거부하면 테이블 하나의 회수 실패가 테넌트 PYTHON 전체를 멈춘다.
    String slotRole = slotRoleName.apply(slot);
    Set<String> overGranted = new TreeSet<>();
    result
        .revokeFailed()
        .forEach(
            (table, rolesLeft) -> {
              if (rolesLeft.contains(slotRole)) {
                overGranted.add(table);
              }
            });
    if (!result.revokeFailed().isEmpty()) {
      // 테이블 이름은 사용자 메시지에 싣지 않는다(로그에만).
      log.error(
          "PYTHON 읽기 권한 회수 실패(tenant={}, 실행 슬롯 {}): {}",
          runAs.tenantId(),
          slot,
          result.revokeFailed());
    }
    if (!overGranted.isEmpty()) {
      throw new PythonReadAccessException("Python 읽기 권한을 회수하지 못한 데이터가 있어 실행을 중단했습니다. 관리자에게 문의하세요.");
    }
    return slot;
  }
}
