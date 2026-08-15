package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.channels.ChatChannel;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code ChatChannel} 의 R1 거부권을 <b>실제 DB·실제 RLS 위에서</b> 검증한다 (P2-e Task 4 리뷰 F3).
 *
 * <p>왜 목 테스트로 부족한가: 거부권의 메커니즘은 "{@code executionRepo.findById} 가
 * {@code TenantContext.runScopedGet} 안에서 <b>실제로 테넌트 스코프된다</b>"는 사실 하나다. 목은
 * {@code findById} 가 무엇을 돌려줄지 테스트가 직접 정하므로 그 사실을 증명할 수 없다 — 스코핑이
 * 통째로 사라져도 목 테스트는 초록이다. 게다가 V104 이전에는 {@code proactive_job_execution} 에
 * 정책이 없어 거부권 자체가 공허했다. <b>정책을 켜는 지금이 이 테스트를 쓸 수 있는 최초 시점이다.</b>
 *
 * <p><b>거울 케이스가 이 파일의 핵심이다.</b> 거부 케이스만 두면 멤버십 해석 실패·조회 예외·무조건
 * 거부 등 <i>어떤</i> 고장이든 {@code PermanentFailure} 라 초록이 된다 — 두 가설을 구분하지 못하는
 * 픽스처는 아무것도 증명하지 못한다는 R18 의 교훈 그대로다. 그래서 "같은 테넌트의 execution 이면
 * 저장된다"를 나란히 두고, 실패 메시지가 <b>멤버십 모호</b>가 아니라 <b>execution 불일치</b>를
 * 가리키는 것까지 못박는다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 붙이지 않는다. 검증 대상인 {@code deliver()} 는 운영에서
 * 워커 스레드가 컨텍스트·트랜잭션 없이 호출한다 — 테스트가 트랜잭션을 열어 주면 GUC 가 공급돼 그
 * 조건이 재현되지 않는다. 픽스처 생성·정리·검증 조회만 {@code inTenantFixture} 로 감싼다.
 */
class ChatChannelTenantVetoTest extends IntegrationTestBase {

  @Autowired private ChatChannel chatChannel;
  @Autowired private DSLContext dsl;

  private long tenantA;
  private long tenantB;
  private Long recipientUserId;

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "veto-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "veto-b");
    recipientUserId = TenantRlsTestSupport.insertUser(dsl, "veto");

    // 수신자의 ACTIVE 멤버십은 tenantA <b>하나뿐</b>이어야 한다. 두 개면 resolveTenantId 가
    // 먼저 실패해(모호) 거부권이 아니라 엉뚱한 분기에서 PermanentFailure 가 나오고, 테스트는
    // 잘못된 이유로 초록이 된다.
    TenantRlsTestSupport.insertActiveMembership(dsl, recipientUserId, tenantA);
  }

  @AfterEach
  void tearDown() {
    inTenantFixture(tenantA, () -> TenantRlsTestSupport.deleteProactiveAiCascade(dsl, tenantA));
    inTenantFixture(tenantB, () -> TenantRlsTestSupport.deleteProactiveAiCascade(dsl, tenantB));
    TenantRlsTestSupport.deleteMembership(dsl, recipientUserId);
    TenantRlsTestSupport.deleteUser(dsl, recipientUserId);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
  }

  @Test
  @DisplayName("타 테넌트의 execution 을 참조하는 발송은 거부되고 proactive_message 가 생기지 않는다")
  void executionFromAnotherTenantIsVetoed() {
    // 잡·실행은 tenantB 소유, 수신자는 tenantA 소속 — 이 밴드가 불가능하게 만들어야 할 조합이다.
    Long executionId = inTenantFixture(tenantB, () -> insertExecution(insertJob(tenantB)));

    // 검증 대상 호출은 픽스처 트랜잭션 <b>밖</b>이다 — 운영의 워커 스레드와 같은 조건.
    DeliveryResult result = chatChannel.deliver(contextWithExecution(executionId));

    assertThat(result)
        .as("타 테넌트 execution 참조는 영구 실패여야 한다")
        .isInstanceOf(DeliveryResult.PermanentFailure.class);
    // 실패 이유를 못박는다 — 멤버십 모호(다른 분기)로 실패해도 타입만으로는 구분되지 않는다.
    assertThat(((DeliveryResult.PermanentFailure) result).details())
        .as("거부 사유가 execution 불일치여야 한다(멤버십 모호가 아니라)")
        .contains("execution " + executionId)
        .contains(String.valueOf(tenantA));

    assertThat(messageCountInTenant(tenantA)).as("수신자 테넌트에 메시지가 생기면 안 된다").isZero();
    assertThat(messageCountInTenant(tenantB)).as("잡 테넌트에도 메시지가 생기면 안 된다").isZero();
  }

  @Test
  @DisplayName("거울 케이스 — 같은 테넌트의 execution 이면 저장된다")
  void executionFromOwnTenantIsStored() {
    Long executionId = inTenantFixture(tenantA, () -> insertExecution(insertJob(tenantA)));

    DeliveryResult result = chatChannel.deliver(contextWithExecution(executionId));

    assertThat(result)
        .as("수신자 테넌트의 execution 은 정상 저장돼야 한다 — 이게 실패하면 위 거부 단언이 공허하다")
        .isInstanceOf(DeliveryResult.Sent.class);
    assertThat(messageCountInTenant(tenantA)).isEqualTo(1);
  }

  // ── 픽스처·검증 조회 ────────────────────────────────────────────────────

  /** 해당 테넌트에서 이 수신자 앞으로 보이는 메시지 수. 조회도 RLS 대상이라 컨텍스트가 필요하다. */
  private int messageCountInTenant(long tenantId) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.fetchCount(
                table(name("proactive_message")),
                field(name("user_id"), Long.class).eq(recipientUserId)));
  }

  private DeliveryContext contextWithExecution(Long executionId) {
    Payload payload =
        new Payload(
            Payload.PayloadType.STANDARD,
            "거부권 검증",
            "요약",
            List.of(),
            List.of(),
            List.of(),
            Map.of("executionId", executionId),
            Map.of());
    return new DeliveryContext(
        1L, UUID.randomUUID(), recipientUserId, null, Optional.empty(), payload);
  }

  /** 호출자가 이미 해당 테넌트 트랜잭션을 열어 둔 안에서 실행된다(bare 삽입). */
  private Long insertJob(long tenantId) {
    return TenantRlsTestSupport.insertProactiveJob(
        dsl, recipientUserId, "거부권검증잡-" + tenantId);
  }

  private Long insertExecution(Long jobId) {
    return TenantRlsTestSupport.insertProactiveExecution(dsl, jobId);
  }
}
