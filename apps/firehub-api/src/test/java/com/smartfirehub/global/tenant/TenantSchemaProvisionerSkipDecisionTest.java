package com.smartfirehub.global.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantSchemaProvisioner.ExistedBefore;
import com.smartfirehub.global.tenant.TenantSchemaProvisioner.HasCompleteDefaultPrivileges;
import com.smartfirehub.global.tenant.TenantSchemaProvisioner.RoleExists;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantSchemaProvisioner#shouldSkipProvisioning} 의 8행 결정표를 고정한다(최종 전체
 * 리뷰 B1 — 이 밴드의 대표 안전 속성 "테넌트 1(data) 에 DDL 없음"을 지키는 단락 조건이
 * 세 번 다시 쓰이는 동안 검증하는 테스트가 하나도 없었다).
 *
 * <p><b>왜 살아있는 DB 로 재현하지 않는가 — {@link TenantSchemaProvisionerSwallowDecisionTest}
 * 와 같은 이유에 더해 하나가 더 있다.</b> {@code app} 롤이 test/dev DB 양쪽에서 슈퍼유저라
 * (실측) 권한 실패를 주입할 수 없는 것은 같고, 여기서는 그것만이 아니다 — 이 조건을 실제
 * 프로덕션 데이터가 있는 테넌트 1({@code data})로 살아있는 DB 에서 시험하는 것 자체가
 * {@code TenantSchemaProvisionerTest} 클래스 Javadoc 이 명시한 규율("테넌트 1 로 프로비저너를
 * 시험하면 data 자체를 건드리게 되므로 절대 쓰지 않는다")과 정면으로 충돌한다. 그래서 판정
 * 로직만 순수 함수로 뽑아 DB 무접촉으로 직접 검증한다.
 *
 * <p>인자가 {@code boolean} 세 개가 아니라 타입 세 개인 이유는 {@code
 * TenantSchemaProvisionerSwallowDecisionTest} 의 {@code ExistedBefore}/{@code ExistsNow} 와
 * 같다 — 순서를 바꿔도 컴파일이 통과하는 것을 막는다.
 */
class TenantSchemaProvisionerSkipDecisionTest {

  @Test
  @DisplayName("스키마가 없었다 — 롤·기본권한 상태와 무관하게 항상 진행한다(단락하지 않는다)")
  void schemaAbsent_neverSkips() {
    // existedBefore=false 인 네 조합 전부: 스키마 자체가 없으므로 무조건 만들어야 한다.
    assertThat(
            TenantSchemaProvisioner.shouldSkipProvisioning(
                new ExistedBefore(false),
                new RoleExists(false),
                new HasCompleteDefaultPrivileges(false)))
        .as("스키마 없음 + 롤 없음 + 기본권한 없음 — 진행해야 한다")
        .isFalse();
    assertThat(
            TenantSchemaProvisioner.shouldSkipProvisioning(
                new ExistedBefore(false),
                new RoleExists(true),
                new HasCompleteDefaultPrivileges(false)))
        .as("스키마 없음 + 롤 있음 + 기본권한 없음 — 진행해야 한다")
        .isFalse();
    assertThat(
            TenantSchemaProvisioner.shouldSkipProvisioning(
                new ExistedBefore(false),
                new RoleExists(false),
                new HasCompleteDefaultPrivileges(true)))
        .as("스키마 없음 + 롤 없음 + 기본권한 있음(모순 조합이지만 방어적으로 확인) — 진행해야 한다")
        .isFalse();
    assertThat(
            TenantSchemaProvisioner.shouldSkipProvisioning(
                new ExistedBefore(false),
                new RoleExists(true),
                new HasCompleteDefaultPrivileges(true)))
        .as("스키마 없음 + 롤 있음 + 기본권한 있음(모순 조합) — 스키마가 없으므로 여전히 진행해야 한다")
        .isFalse();
  }

  @Test
  @DisplayName("스키마는 있었지만 executor 롤이 아직 없다 — 줄 게 없으므로 단락한다")
  void schemaExisted_roleAbsent_alwaysSkips() {
    // 롤이 아직 프로비저닝되지 않은 신규 테넌트(#383 운영자 절차 지연) — 이 상태에서 6문장을
    // 돌 이유가 없다(어차피 트랜잭션 안에서도 roleExists(tx,...) 가 다시 걸러낸다).
    assertThat(
            TenantSchemaProvisioner.shouldSkipProvisioning(
                new ExistedBefore(true),
                new RoleExists(false),
                new HasCompleteDefaultPrivileges(false)))
        .as("스키마 있음 + 롤 없음 — 단락한다")
        .isTrue();
  }

  @Test
  @DisplayName("스키마도 있고 롤도 있는데 기본권한이 아직 불완전하다 — 자가치유 경로, 단락하지 않는다")
  void schemaExisted_roleExists_privilegesIncomplete_doesNotSkip() {
    // 라운드 2 리뷰가 잡은 바로 그 중간 상태: 운영자가 GRANT USAGE 만 손으로 줬거나 아직
    // 아무 권한도 안 준 경우 — ALTER DEFAULT PRIVILEGES 가 아직 안 걸렸으므로 반드시
    // 6문장을 다시 돌려 자가치유해야 한다. 이 케이스가 통째로 지워지는 변이(단순히 "롤이
    // 있으면 항상 단락"으로 되돌리는 것)를 이 단언이 잡는다.
    assertThat(
            TenantSchemaProvisioner.shouldSkipProvisioning(
                new ExistedBefore(true),
                new RoleExists(true),
                new HasCompleteDefaultPrivileges(false)))
        .as("스키마 있음 + 롤 있음 + 기본권한 불완전 — 자가치유해야 하므로 단락하면 안 된다")
        .isFalse();
  }

  @Test
  @DisplayName("스키마도 있고 롤도 있고 기본권한도 이미 완전하다 — 할 일이 없으므로 단락한다")
  void schemaExisted_roleExists_privilegesComplete_skips() {
    // 이 밴드의 대표 안전 속성이 지키는 바로 그 경로다 — 테넌트 1(data)의 매 createTable 마다
    // 여기서 단락돼야 GRANT/ALTER DEFAULT PRIVILEGES 가 86개 테이블을 상대로 재실행되지
    // 않는다. 이 단언을 통째로 지우거나 이 케이스가 false 로 뒤집히는 변이가 R9 가 막으려던
    // 결함(임포트 중 AccessExclusiveLock 재획득)을 되살린다.
    assertThat(
            TenantSchemaProvisioner.shouldSkipProvisioning(
                new ExistedBefore(true),
                new RoleExists(true),
                new HasCompleteDefaultPrivileges(true)))
        .as("스키마 있음 + 롤 있음 + 기본권한 완전 — 단락해야 한다(테넌트 1 안전 속성의 핵심)")
        .isTrue();
  }
}
