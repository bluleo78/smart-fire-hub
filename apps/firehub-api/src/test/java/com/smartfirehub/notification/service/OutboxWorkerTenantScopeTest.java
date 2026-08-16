package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.notification.AuthStrategy;
import com.smartfirehub.notification.Channel;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.channels.ChatChannel;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.NotificationOutboxRepository.NotificationOutboxRow;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongPredicate;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 배경 경로 배선의 <b>판별력 있는</b> 검증 — 워커가 outbox 행의 테넌트로 스코프를 여는가.
 *
 * <p><b>왜 별도 테스트인가.</b> RLS 정책은 아직 꺼져 있다(Task 6). 그 상태에서는 "배달이 됐다"만
 * 봐서는 스코프가 열렸는지 알 수 없다 — 컨텍스트가 없어도 쿼리가 전부 통과하기 때문이다. 그래서
 * 채널을 스텁으로 갈아 끼우고 <b>배달 시점의 {@link TenantContext#get()} 을 직접 기록</b>한다.
 * 스코프 배선을 걷어내면 이 단언은 null 로 즉시 깨진다(판별력 확인 완료).
 *
 * <p><b>왜 스프링 빈이 아니라 손으로 조립하는가.</b> {@link ChannelRegistry} 는
 * {@code List<Channel>} 을 {@code EnumMap} 에 나중 것이 이기는 순서로 담으므로, 경쟁하는 CHAT 빈을
 * 추가하면 결과가 빈 순서에 좌우된다. 워커의 생성자는 평범한 인자뿐이라 진짜 리포지토리·진짜 DB 를
 * 그대로 쓰면서 채널만 바꿔 끼우는 것이 더 정직하고 안정적이다.
 *
 * <p><b>테넌트 목록을 좁히는 이유(R5).</b> {@code runOneBatch} 는 일감이 있는 <b>모든</b> 테넌트를
 * 돈다. 공유 테스트 DB 에는 다른 세션이 흘린 tenant_id=1 의 PENDING 행이 수천 건 쌓여 있어, 그대로
 * 부르면 <b>남의 행을 클레임해 SENT 로 바꿔 버린다</b>. 그래서 {@code tenantIdsWithStatus} 만
 * 가로채 이 테스트가 만든 두 테넌트로 좁힌다 — 순회 로직 자체는 그대로 검증된다.
 *
 * <p>이 테스트가 세우는 계약("워커가 outbox 행의 테넌트로 스코프를 연다")에 Task 4 의
 * {@code ChatChannel} 임시방편 제거가 의존한다.
 *
 * <p><b>P2-f Task 4 승계.</b> {@code tenant/ChatChannelTenantVetoTest} 가 검증하던 R9 의 execution
 * 교차테넌트 검사도 이 파일로 옮겼다. 원래 그 테스트는 {@code ChatChannel} 을 직접 부르며 "채널이
 * 스스로 멤버십으로 테넌트를 해석한다"를 전제했는데, 임시방편이 사라지면서 그 전제가 뒤집혔다 —
 * 이제 검증해야 할 것은 <b>워커가 연 스코프 안에서 execution 이 걸러지는가</b>이므로, 워커를 통과하는
 * 이 파일이 정직한 자리다. 스텁이 아닌 <b>진짜 {@link ChatChannel} 빈</b>을 끼워 돌린다.
 *
 * <p>클래스 레벨 {@code @Transactional} 은 여기서도 금지다. 검증 대상인 워커는 운영에서
 * {@code @Scheduled} 스레드가 컨텍스트·트랜잭션 없이 부르고, 스코프를 <b>스스로 만드는 주체</b>가
 * 바로 그 워커다 — 테스트가 트랜잭션을 열어 GUC 를 공급해 버리면 배선이 통째로 사라져도 초록이 된다.
 * 픽스처 생성·정리·검증 조회만 {@code inTenantFixture} 로 감싼다.
 */
class OutboxWorkerTenantScopeTest extends IntegrationTestBase {

  @Autowired private NotificationOutboxRepository outboxRepo;
  @Autowired private UserChannelBindingRepository bindingRepo;
  @Autowired private BackoffPolicy backoff;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DSLContext dsl;
  @Autowired private ChatChannel chatChannel;
  @Autowired private TenantScopedRunner tenantRunner;

  private long tenantA;
  private long tenantB;
  private Long recipientUserId;

  @BeforeEach
  void createScratchTenants() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "outbox-scope-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "outbox-scope-b");
    // 수신자에게 멤버십을 <b>주지 않는다</b>. P2-e 에는 ChatChannel 이 멤버십으로 테넌트를 추측해서
    // 멤버십이 정확히 1개가 아니면 발송 자체가 실패했다. 임시방편이 사라졌다는 가장 직접적인 증거가
    // "멤버십 없이도 배달된다"이므로 픽스처에서 의도적으로 뺀다.
    recipientUserId = TenantRlsTestSupport.insertUser(dsl, "outbox-scope");
  }

  @AfterEach
  void cleanupScratchTenants() {
    // 삭제 순서는 FK 역순 — proactive_message/execution/job 을 먼저 지우지 않으면 deleteUser 와
    // deleteTenants 가 23503 으로 터진다.
    inTenantFixture(tenantA, () -> TenantRlsTestSupport.deleteProactiveAiCascade(dsl, tenantA));
    inTenantFixture(tenantB, () -> TenantRlsTestSupport.deleteProactiveAiCascade(dsl, tenantB));
    inTenantFixture(tenantA, () -> TenantRlsTestSupport.deleteChannelCascade(dsl, tenantA));
    inTenantFixture(tenantB, () -> TenantRlsTestSupport.deleteChannelCascade(dsl, tenantB));
    TenantRlsTestSupport.deleteUser(dsl, recipientUserId);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
  }

  /**
   * 두 테넌트에 한 건씩 쌓아 두고 <b>컨텍스트 없이</b> {@code runOneBatch} 를 부른다. 워커는 스스로
   * 테넌트를 순회하며, 각 행은 <b>자기 테넌트의 컨텍스트 안에서</b> 배달돼야 한다.
   *
   * <p>클레임에 테넌트 술어가 없으면(정책이 꺼진 지금은 그게 기본 동작이다) A 의 순회에서 B 의
   * 행까지 잡혀 B 의 행이 A 컨텍스트로 배달된다 — 그 회귀도 이 단언이 잡는다.
   */
  @Test
  void runOneBatch_deliversEachRowInsideItsOwnTenantScope() {
    UUID corrA = insertPendingRow(tenantA, "scope-a");
    UUID corrB = insertPendingRow(tenantB, "scope-b");

    RecordingChannel channel = new RecordingChannel();
    NotificationDispatchWorker worker = workerWith(channel, scopedRepo(t -> false));

    // 프로덕션의 @Scheduled 스레드 재현 — 컨텍스트가 전혀 없다.
    TenantContext.clear();
    worker.runOneBatch();

    assertThat(channel.tenantOf(corrA))
        .as("테넌트 A 의 행은 A 컨텍스트 안에서 배달돼야 한다 (null 이면 스코프 배선 없음)")
        .isEqualTo(tenantA);
    assertThat(channel.tenantOf(corrB))
        .as("테넌트 B 의 행은 B 컨텍스트 안에서 배달돼야 한다")
        .isEqualTo(tenantB);
  }

  /**
   * 순회 레벨 배치 중단 방지 — 한 테넌트의 클레임이 터져도 나머지 테넌트는 계속 처리돼야 한다.
   *
   * <p>기존 행 단위 try/catch 로는 이 실패를 잡을 수 없다(클레임은 행 루프 <b>바깥</b>이다).
   * 순회 레벨 방어가 없으면 A 의 예외가 {@code runOneBatch} 를 통째로 뚫고 나가 B 는 이 주기에
   * 영원히 처리되지 않는다.
   */
  @Test
  void runOneBatch_oneTenantFailureDoesNotStopOthers() {
    insertPendingRow(tenantA, "boom-a");
    UUID corrB = insertPendingRow(tenantB, "boom-b");

    RecordingChannel channel = new RecordingChannel();
    NotificationDispatchWorker worker = workerWith(channel, scopedRepo(t -> t == tenantA));

    TenantContext.clear();
    worker.runOneBatch();

    assertThat(channel.tenantOf(corrB))
        .as("한 테넌트의 실패가 다른 테넌트의 배치를 멈추면 안 된다")
        .isEqualTo(tenantB);
  }

  /**
   * 승계 — 타 테넌트의 execution 을 참조하는 행은 거부되고 {@code proactive_message} 가 생기지 않는다.
   *
   * <p>outbox 행은 tenantA, 잡·실행은 tenantB 소유다. 워커가 tenantA 스코프를 열고 진짜
   * {@link ChatChannel} 을 부르면, R9 의 검사가 현재 컨텍스트에서 execution 을 못 찾아 영구 실패로
   * 마감해야 한다. 이 조합이 이 밴드가 불가능하게 만들어야 할 것이다.
   */
  @Test
  void crossTenantExecutionIsVetoedInsideWorkerScope() {
    Long executionId = inTenantFixture(tenantB, () -> insertExecution(tenantB));
    UUID corr = insertPendingRow(tenantA, "veto-a", recipientUserId, Map.of("executionId", executionId));

    NotificationDispatchWorker worker = workerWith(chatChannel, scopedRepo(t -> false));

    TenantContext.clear();
    worker.runOneBatch();

    assertThat(statusOf(tenantA, corr))
        .as("타 테넌트 execution 참조는 영구 실패여야 한다")
        .isEqualTo("PERMANENT_FAILURE");
    // 실패 이유를 못박는다 — 어떤 고장이든 PERMANENT_FAILURE 라 타입만으로는 구분되지 않는다.
    assertThat(lastErrorOf(tenantA, corr))
        .as("거부 사유가 execution 불일치를 가리켜야 한다")
        .contains("execution " + executionId)
        .contains(String.valueOf(tenantA));

    assertThat(messageCountInTenant(tenantA)).as("outbox 행의 테넌트에 메시지가 생기면 안 된다").isZero();
    assertThat(messageCountInTenant(tenantB)).as("잡 테넌트에도 메시지가 생기면 안 된다").isZero();

    // 판별력의 핵심 단언. 위 두 단언은 "이 수신자 앞으로 온 메시지" 를 세므로 다른 이유로도 0 이
    // 될 수 있다. 여기서는 문제의 execution 을 <b>참조하는</b> 행이 어느 테넌트에도 없음을 못박는다 —
    // 검사를 걷어내면 tenant_id=A / execution_id=B의것 인 행이 실제로 생겨 이 단언이 빨개진다.
    assertThat(messageCountForExecution(tenantA, executionId))
        .as("검사를 걷어내면 여기 교차테넌트 행이 실제로 생긴다 (FK 는 막지 못한다)")
        .isZero();
    assertThat(messageCountForExecution(tenantB, executionId)).isZero();
  }

  /**
   * <b>A-1 사실 고정.</b> FK 는 교차테넌트 execution 참조를 막지 <b>못한다</b> — 따라서
   * {@link ChatChannel} 의 명시 검사가 이 경로의 유일한 방어다.
   *
   * <p>원장 R9 · 조사 §8 · Task 4 는 오랫동안 "{@code proactive_message_execution_id_fkey} 가
   * 있고 양쪽 다 RLS 대상이니 타 테넌트 execution 참조는 INSERT 에서 자연히 막힌다" 를 근거로
   * 삼았다. 그 서술은 <b>거짓</b>이다: PostgreSQL 의 참조 무결성 검사는 정책을 우회해 부모 행을
   * 찾으므로, 현재 컨텍스트에서 SELECT 로 보이지도 않는 부모를 참조하는 INSERT 가 통과한다.
   *
   * <p>이 테스트는 그 사실 자체를 코드로 고정한다. 언젠가 PostgreSQL 이 동작을 바꾸거나 복합 FK
   * ({@code (tenant_id, execution_id)}) 가 도입되면 이 테스트가 빨개지고, 그때 비로소 앱 레벨 검사를
   * 걷어내도 되는지 재검토할 수 있다. 위 거부 케이스와 짝이다 — 저쪽은 "검사가 막는다", 이쪽은
   * "DB 는 막지 않는다".
   */
  @Test
  void foreignKeyDoesNotBlockCrossTenantExecutionReference() {
    Long executionId = inTenantFixture(tenantB, () -> insertExecution(tenantB));

    // 테넌트 A 컨텍스트: B 의 execution 은 RLS 로 보이지 않는다.
    assertThat(inTenantFixture(tenantA, () -> executionVisible(executionId)))
        .as("A 컨텍스트에서 B 의 execution 은 보이지 않아야 한다 (RLS 가 살아 있다는 전제)")
        .isFalse();

    // 그런데도 그 id 를 참조하는 INSERT 는 성공한다 — RI 검사가 RLS 를 우회하기 때문이다.
    Long messageId =
        inTenantFixture(
            tenantA,
            () ->
                (Long)
                    dsl.fetchValue(
                        "insert into proactive_message (user_id, execution_id, title, content,"
                            + " message_type, read) values (?, ?, 'A-1 사실 고정', '{}'::jsonb,"
                            + " 'REPORT', false) returning id",
                        recipientUserId,
                        executionId));

    assertThat(messageId).as("교차테넌트 참조 INSERT 가 FK 로 막히지 않는다").isNotNull();
    assertThat(messageCountForExecution(tenantA, executionId))
        .as("행은 outbox 테넌트(A)로 심기고 execution 은 B 의 것이다 — 어긋난 행이 실제로 존재한다")
        .isEqualTo(1);
    // 이 어긋난 행은 @AfterEach 의 deleteProactiveAiCascade(tenantA) 가 지운다.
  }

  /**
   * 승계 — 거울 케이스. 같은 테넌트의 execution 이면 저장된다.
   *
   * <p><b>이 케이스가 위 거부 단언을 떠받친다.</b> 거부 케이스만 두면 조회 예외·무조건 거부·스코프
   * 미배선 등 <i>어떤</i> 고장이든 영구 실패라 초록이 된다 — 두 가설을 구분하지 못하는 픽스처는
   * 아무것도 증명하지 못한다. 특히 이 단언이 빨개지면 원인은 "거부권이 과하다"가 아니라
   * {@code ProactiveJobExecutionRepository} 의 트랜잭션 경계가 사라져 GUC 가 안 심긴 것이다.
   */
  @Test
  void sameTenantExecutionIsStoredInsideWorkerScope() {
    Long executionId = inTenantFixture(tenantA, () -> insertExecution(tenantA));
    UUID corr = insertPendingRow(tenantA, "mirror-a", recipientUserId, Map.of("executionId", executionId));

    NotificationDispatchWorker worker = workerWith(chatChannel, scopedRepo(t -> false));

    TenantContext.clear();
    worker.runOneBatch();

    assertThat(statusOf(tenantA, corr))
        .as("자기 테넌트의 execution 은 정상 발송돼야 한다 — 이게 실패하면 위 거부 단언이 공허하다")
        .isEqualTo("SENT");
    assertThat(messageCountInTenant(tenantA)).isEqualTo(1);
  }

  // ── 검증 조회 ──────────────────────────────────────────────────────────

  /** 해당 테넌트에서 이 수신자 앞으로 보이는 메시지 수. 조회도 RLS 대상이라 컨텍스트가 필요하다. */
  private int messageCountInTenant(long tenantId) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.fetchCount(
                table(name("proactive_message")),
                field(name("user_id"), Long.class).eq(recipientUserId)));
  }

  /**
   * 해당 테넌트에서 <b>이 execution 을 참조하는</b> 메시지 수. 수신자가 아니라 execution 으로 세는
   * 것이 A-1 의 관심사다 — 교차테넌트 참조 행이 생겼는지를 직접 본다.
   */
  private int messageCountForExecution(long tenantId, Long executionId) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.fetchCount(
                table(name("proactive_message")),
                field(name("execution_id"), Long.class).eq(executionId)));
  }

  /** 호출자가 연 테넌트 컨텍스트에서 이 execution 이 RLS 를 통과해 보이는가. */
  private boolean executionVisible(Long executionId) {
    return TenantRlsTestSupport.rowExists(dsl, "proactive_job_execution", "id", executionId);
  }

  private String statusOf(long tenantId, UUID correlationId) {
    return outboxField(tenantId, correlationId, "status");
  }

  private String lastErrorOf(long tenantId, UUID correlationId) {
    return outboxField(tenantId, correlationId, "last_error");
  }

  private String outboxField(long tenantId, UUID correlationId, String column) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.fetchValue(
                dsl.select(field(name(column), String.class))
                    .from(table(name("notification_outbox")))
                    .where(field(name("correlation_id"), UUID.class).eq(correlationId))));
  }

  /** 호출자가 이미 해당 테넌트 트랜잭션을 열어 둔 안에서 실행된다(bare 삽입). */
  private Long insertExecution(long tenantId) {
    Long jobId = TenantRlsTestSupport.insertProactiveJob(dsl, recipientUserId, "scope-job-" + tenantId);
    return TenantRlsTestSupport.insertProactiveExecution(dsl, jobId);
  }

  /** 진짜 리포지토리·진짜 DB 를 쓰고 채널만 스텁으로 바꾼 워커. */
  private NotificationDispatchWorker workerWith(
      Channel channel, NotificationOutboxRepository repo) {
    return new NotificationDispatchWorker(
        repo,
        tenantRunner,
        bindingRepo,
        new ChannelRegistry(List.of(channel)),
        backoff,
        objectMapper,
        20,
        true);
  }

  /**
   * 실제 리포지토리를 감싸되 (a) 순회 대상 테넌트를 이 테스트가 만든 둘로 좁히고, (b)
   * {@code failClaimFor} 에 해당하는 테넌트의 클레임을 실패시키는 프록시.
   */
  private NotificationOutboxRepository scopedRepo(LongPredicate failClaimFor) {
    return (NotificationOutboxRepository)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {NotificationOutboxRepository.class},
            (proxy, method, args) -> {
              if (method.getName().equals("tenantIdsWithStatus")) {
                return List.of(tenantA, tenantB);
              }
              if (method.getName().equals("claimDue") && failClaimFor.test((Long) args[2])) {
                throw new IllegalStateException("테넌트 " + args[2] + " 클레임 강제 실패");
              }
              try {
                return method.invoke(outboxRepo, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  /** 주어진 테넌트 컨텍스트 안에서 PENDING 행 하나를 만들고 correlationId 를 돌려준다. */
  private UUID insertPendingRow(long tenantId, String keyPrefix) {
    return insertPendingRow(tenantId, keyPrefix, null, Map.of());
  }

  /** 수신자·payload metadata 까지 지정하는 버전 — 승계된 execution 검사 케이스가 쓴다. */
  private UUID insertPendingRow(
      long tenantId, String keyPrefix, Long userId, Map<String, Object> metadata) {
    UUID corr = UUID.randomUUID();
    String payloadJson;
    try {
      payloadJson =
          objectMapper.writeValueAsString(
              new Payload(
                  Payload.PayloadType.STANDARD,
                  "스코프 검증",
                  "요약",
                  List.of(),
                  List.of(),
                  List.of(),
                  metadata,
                  Map.of()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    NotificationOutboxRow row =
        new NotificationOutboxRow(
            null,
            keyPrefix + "-" + corr,
            corr,
            "TEST_SCOPE",
            null,
            ChannelType.CHAT,
            userId,
            null,
            null,
            null,
            payloadJson,
            "STANDARD",
            "PENDING",
            0,
            Instant.now());
    TenantContext.runScoped(tenantId, () -> outboxRepo.insertIfAbsent(row));
    // DB 시계가 JVM 보다 앞설 때 next_attempt_at 이 "미래"라 클레임되지 않는 것을 막는다.
    inTenantFixture(tenantId, () -> TenantRlsTestSupport.makeOutboxRowDue(dsl, corr));
    return corr;
  }

  /** 배달 시점의 테넌트 컨텍스트를 correlationId 별로 기록하는 스텁 채널. */
  private static class RecordingChannel implements Channel {

    /** correlationId → 배달 시점의 테넌트. 값이 null 일 수 있어 {@link ConcurrentHashMap} 은 쓰지 않는다. */
    private final Map<UUID, Long> delivered = new HashMap<>();

    @Override
    public ChannelType type() {
      return ChannelType.CHAT;
    }

    @Override
    public AuthStrategy authStrategy() {
      return AuthStrategy.NONE;
    }

    @Override
    public DeliveryResult deliver(DeliveryContext ctx) {
      delivered.put(ctx.correlationId(), TenantContext.get());
      return new DeliveryResult.Sent("stub-" + ctx.outboxId());
    }

    Long tenantOf(UUID correlationId) {
      return delivered.get(correlationId);
    }
  }
}
