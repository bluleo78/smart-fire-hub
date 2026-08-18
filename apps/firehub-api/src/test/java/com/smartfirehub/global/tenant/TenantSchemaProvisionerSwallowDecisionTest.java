package com.smartfirehub.global.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantSchemaProvisioner#shouldSwallowCreationRace} 의 결정표를 고정한다(라운드 2
 * 리뷰 BLOCKER).
 *
 * <p><b>왜 살아있는 DB 로 재현하지 않는가.</b> 자가치유 경로(B1)의 실패 전파를 검증하려면
 * {@code ensureCurrentTenantSchema()} 의 트랜잭션이 진짜로 실패해야 하는데, 이 트랜잭션을
 * 실행하는 {@code app} 롤은 이 저장소의 test/dev DB 양쪽에서 <b>슈퍼유저</b>다(실측:
 * {@code rolsuper=t, rolbypassrls=t}). 슈퍼유저는 GRANT/ALTER DEFAULT PRIVILEGES 류를 대상
 * 존재 여부와 무관하게(대상이 실재하는 한) 항상 통과시키므로, 권한 거부로 실패를 주입할
 * 방법이 사실상 없다. 반면 동시성 기반 실패(역할 동시 드롭·데드락·타임아웃)는 타이밍에
 * 의존해 결정적으로 재현할 수 없다 — 그런 테스트는 그 자체로 이 저장소가 피하는 플레이크가
 * 된다.
 *
 * <p>그래서 판정 로직 자체를 순수 함수로 뽑아 직접 검증한다. {@code
 * shouldSwallowCreationRace} 는 package-private 이라 이 테스트는 <b>같은 패키지</b>
 * ({@code com.smartfirehub.global.tenant})에 둔다 — {@code com.smartfirehub.tenant} 의 다른
 * 프로비저너 테스트들과 패키지가 다른 것은 의도다.
 */
class TenantSchemaProvisionerSwallowDecisionTest {

  @Test
  @DisplayName("자가치유 경로(진입 시 스키마가 이미 있었다)의 실패는 항상 전파한다 — 결과 상태와 무관하다")
  void selfHealPathAlwaysPropagates() {
    // existedBefore=true 는 B1 이 연 자가치유 경로다. 트랜잭션이 왜 실패했든(권한 문제, 동시
    // 롤 드롭, 데드락, 타임아웃) 조용히 삼키면 권한이 영영 안 걸린 채 성공으로 보고된다 —
    // 그 무성 실패를 막는 것이 이 단언의 목적이다.
    assertThat(TenantSchemaProvisioner.shouldSwallowCreationRace(true, true))
        .as("자가치유 경로 — 실패 후 스키마가 여전히 존재해도 삼키지 않는다")
        .isFalse();
    assertThat(TenantSchemaProvisioner.shouldSwallowCreationRace(true, false))
        .as("자가치유 경로 — 스키마가 사라졌다면 더더욱 삼키지 않는다")
        .isFalse();
  }

  @Test
  @DisplayName("진짜 생성 경합(진입 시 없었는데 실패 후엔 존재)만 삼킨다")
  void onlyGenuineCreationRaceIsSwallowed() {
    // existedBefore=false 인데 실패 직후 존재한다는 것은, 이 호출과 동시에 다른 트랜잭션이
    // CREATE SCHEMA IF NOT EXISTS 로 먼저 만들었다는 뜻이다(23505, pg_namespace_nspname_index)
    // — 그건 성공과 같은 상태이므로 삼킨다.
    assertThat(TenantSchemaProvisioner.shouldSwallowCreationRace(false, true))
        .as("들어올 때 없었고 실패 후 존재한다 — 다른 트랜잭션이 먼저 만든 진짜 생성 경합")
        .isTrue();
  }

  @Test
  @DisplayName("들어올 때도 없었고 실패 후에도 없으면 삼키지 않는다 — 진짜 실패다")
  void neitherExistedIsNeverSwallowed() {
    assertThat(TenantSchemaProvisioner.shouldSwallowCreationRace(false, false))
        .as("스키마가 끝내 안 생겼다 — 생성 경합이 아니라 진짜 실패이므로 전파한다")
        .isFalse();
  }
}
