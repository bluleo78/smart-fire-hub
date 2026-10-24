package com.smartfirehub.proactive.repository;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.proactive.dto.ProactiveMessageResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProactiveMessageRepository 통합 테스트. 실제 DB를 사용해
 * create/findUnread/countUnread/markAsRead/markAllAsRead/findByUserId 메서드를 커버한다.
 */
@Transactional
class ProactiveMessageRepositoryTest extends IntegrationTestBase {

  @Autowired private ProactiveMessageRepository repository;
  @Autowired private ProactiveJobRepository jobRepository;
  @Autowired private ProactiveJobExecutionRepository executionRepository;
  @Autowired private DSLContext dsl;

  private Long userId;

  @BeforeEach
  void setUp() {
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "pmrepo_" + System.nanoTime())
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "PM Repo Tester")
            .set(USER.EMAIL, "pmrepo_" + System.nanoTime() + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
  }

  // -----------------------------------------------------------------------
  // create
  // -----------------------------------------------------------------------

  @Test
  void create_withContent_returnsGeneratedId() {
    Long id = repository.create(userId, null, "Test Title", Map.of("key", "value"), "REPORT");
    assertThat(id).isNotNull().isPositive();
  }

  @Test
  void create_withNullContent_usesEmptyJsonObject() {
    // content=null → "{}" 직렬화 분기 커버
    Long id = repository.create(userId, null, "Null Content", null, "REPORT");
    assertThat(id).isNotNull().isPositive();
  }

  @Test
  void create_withNullMessageType_defaultsToReport() {
    // messageType=null → "REPORT" 기본값 분기 커버
    Long id = repository.create(userId, null, "No Type", Map.of(), null);
    assertThat(id).isNotNull().isPositive();
  }

  // -----------------------------------------------------------------------
  // findUnreadByUserId
  // -----------------------------------------------------------------------

  @Test
  void findUnreadByUserId_returnsOnlyUnreadMessages() {
    repository.create(userId, null, "Unread 1", Map.of("x", 1), "REPORT");
    repository.create(userId, null, "Unread 2", Map.of("x", 2), "ALERT");

    List<ProactiveMessageResponse> unread = repository.findUnreadByUserId(userId);

    assertThat(unread).hasSize(2);
    assertThat(unread).allMatch(m -> !m.read());
    assertThat(unread)
        .extracting(ProactiveMessageResponse::title)
        .containsExactlyInAnyOrder("Unread 1", "Unread 2");
  }

  @Test
  void findUnreadByUserId_afterMarkAsRead_excludesReadMessage() {
    Long msgId = repository.create(userId, null, "Will Be Read", Map.of(), "REPORT");

    // 읽음 처리 후 unread 목록에서 사라져야 한다
    repository.markAsRead(msgId, userId);

    List<ProactiveMessageResponse> unread = repository.findUnreadByUserId(userId);
    assertThat(unread).isEmpty();
  }

  @Test
  void findUnreadByUserId_otherUserMessages_notReturned() {
    // 다른 사용자 메시지는 반환되지 않아야 한다
    Long otherUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "pmrepo_other_" + System.nanoTime())
            .set(USER.PASSWORD, "pw")
            .set(USER.NAME, "Other")
            .set(USER.EMAIL, "pmrepo_other_" + System.nanoTime() + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    repository.create(otherUserId, null, "Other's Message", Map.of(), "REPORT");

    List<ProactiveMessageResponse> unread = repository.findUnreadByUserId(userId);
    assertThat(unread).isEmpty();
  }

  // -----------------------------------------------------------------------
  // countUnreadByUserId
  // -----------------------------------------------------------------------

  @Test
  void countUnreadByUserId_noMessages_returnsZero() {
    int count = repository.countUnreadByUserId(userId);
    assertThat(count).isZero();
  }

  @Test
  void countUnreadByUserId_withMessages_returnsCorrectCount() {
    repository.create(userId, null, "Msg 1", Map.of(), "REPORT");
    repository.create(userId, null, "Msg 2", Map.of(), "REPORT");

    int count = repository.countUnreadByUserId(userId);
    assertThat(count).isEqualTo(2);
  }

  @Test
  void countUnreadByUserId_afterMarkAsRead_decrements() {
    Long id1 = repository.create(userId, null, "A", Map.of(), "REPORT");
    repository.create(userId, null, "B", Map.of(), "REPORT");

    repository.markAsRead(id1, userId);

    int count = repository.countUnreadByUserId(userId);
    assertThat(count).isEqualTo(1);
  }

  // -----------------------------------------------------------------------
  // markAsRead
  // -----------------------------------------------------------------------

  @Test
  void markAsRead_setsReadTrueAndReadAt() {
    Long id = repository.create(userId, null, "Mark Me", Map.of(), "REPORT");

    repository.markAsRead(id, userId);

    List<ProactiveMessageResponse> all = repository.findByUserId(userId, 10, 0, false);
    ProactiveMessageResponse msg =
        all.stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    assertThat(msg.read()).isTrue();
    assertThat(msg.readAt()).isNotNull();
  }

  @Test
  void markAsRead_wrongUser_doesNotMarkRead() {
    Long id = repository.create(userId, null, "Mine", Map.of(), "REPORT");

    // 다른 userId로 markAsRead 호출 → where 조건 불일치로 업데이트 안 됨
    repository.markAsRead(id, userId + 9999L);

    int count = repository.countUnreadByUserId(userId);
    assertThat(count).isEqualTo(1); // 여전히 unread
  }

  // -----------------------------------------------------------------------
  // markAllAsRead
  // -----------------------------------------------------------------------

  @Test
  void markAllAsRead_marksAllUnreadMessages() {
    repository.create(userId, null, "A", Map.of(), "REPORT");
    repository.create(userId, null, "B", Map.of(), "REPORT");
    repository.create(userId, null, "C", Map.of(), "ALERT");

    repository.markAllAsRead(userId);

    int count = repository.countUnreadByUserId(userId);
    assertThat(count).isZero();
  }

  @Test
  void markAllAsRead_noMessages_doesNotFail() {
    // 읽을 메시지가 없어도 예외 없이 동작해야 한다
    repository.markAllAsRead(userId);
    assertThat(repository.countUnreadByUserId(userId)).isZero();
  }

  // -----------------------------------------------------------------------
  // findByUserId — pagination
  // -----------------------------------------------------------------------

  @Test
  void findByUserId_withLimitAndOffset_returnsPaginatedResult() {
    repository.create(userId, null, "First", Map.of(), "REPORT");
    repository.create(userId, null, "Second", Map.of(), "REPORT");
    repository.create(userId, null, "Third", Map.of(), "REPORT");

    // limit=2, offset=0 → 2개 반환
    List<ProactiveMessageResponse> page1 = repository.findByUserId(userId, 2, 0, false);
    assertThat(page1).hasSize(2);

    // limit=2, offset=2 → 나머지 1개
    List<ProactiveMessageResponse> page2 = repository.findByUserId(userId, 2, 2, false);
    assertThat(page2).hasSize(1);
  }

  @Test
  void findByUserId_includesReadAndUnreadMessages() {
    Long id1 = repository.create(userId, null, "Unread", Map.of(), "REPORT");
    Long id2 = repository.create(userId, null, "Read", Map.of(), "REPORT");

    repository.markAsRead(id2, userId);

    List<ProactiveMessageResponse> all = repository.findByUserId(userId, 10, 0, false);
    assertThat(all).hasSize(2);

    // 읽은 것과 안 읽은 것 모두 포함
    assertThat(all).anyMatch(m -> m.id().equals(id1) && !m.read());
    assertThat(all).anyMatch(m -> m.id().equals(id2) && m.read());
  }

  @Test
  void findByUserId_contentDeserialized_correctly() {
    Map<String, Object> content = Map.of("summary", "test summary", "count", 42);
    Long id = repository.create(userId, null, "Rich Content", content, "REPORT");

    List<ProactiveMessageResponse> messages = repository.findByUserId(userId, 10, 0, false);
    ProactiveMessageResponse msg =
        messages.stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();

    assertThat(msg.content()).containsKey("summary");
    assertThat(msg.content().get("summary")).isEqualTo("test summary");
  }

  // -----------------------------------------------------------------------
  // findByUserId — unreadOnly 필터 (#520)
  // -----------------------------------------------------------------------

  @Test
  void findByUserId_unreadOnlyTrue_excludesReadMessages() {
    Long unreadId = repository.create(userId, null, "Unread Msg", Map.of(), "REPORT");
    Long readId = repository.create(userId, null, "Read Msg", Map.of(), "REPORT");
    repository.markAsRead(readId, userId);

    List<ProactiveMessageResponse> unreadOnly = repository.findByUserId(userId, 10, 0, true);

    assertThat(unreadOnly).extracting(ProactiveMessageResponse::id).containsExactly(unreadId);
  }

  @Test
  void findByUserId_unreadOnlyFalse_includesAllMessages() {
    Long unreadId = repository.create(userId, null, "Unread Msg", Map.of(), "REPORT");
    Long readId = repository.create(userId, null, "Read Msg", Map.of(), "REPORT");
    repository.markAsRead(readId, userId);

    List<ProactiveMessageResponse> all = repository.findByUserId(userId, 10, 0, false);

    assertThat(all)
        .extracting(ProactiveMessageResponse::id)
        .containsExactlyInAnyOrder(unreadId, readId);
  }

  // -----------------------------------------------------------------------
  // canViewReport (WD-162)
  // -----------------------------------------------------------------------

  private Long createOtherUser(String prefix) {
    String unique = prefix + "_" + System.nanoTime();
    return dsl.insertInto(USER)
        .set(USER.USERNAME, unique)
        .set(USER.PASSWORD, "pw")
        .set(USER.NAME, "Recipient")
        .set(USER.EMAIL, unique + "@example.com")
        .returning(USER.ID)
        .fetchOne()
        .getId();
  }

  /** userId(작성자) 소유 작업의 COMPLETED 리포트 실행을 만든다 — 채팅 리포트가 가리키는 실행. */
  private Long createOwnedReportExecution() {
    Long jobId =
        jobRepository.create(
            userId, "주간 현황", "prompt", null, "0 0 9 * * *", "Asia/Seoul", true, null, Map.of());
    Long execId = executionRepository.create(jobId);
    executionRepository.updateResult(
        execId,
        "COMPLETED",
        Map.of("title", "주간 리포트", "summary", "요약", "htmlContent", "<h1>body</h1>"),
        LocalDateTime.now());
    return execId;
  }

  /**
   * 같은 리포트를 작성자와 다른 수신자가 함께 받았을 때 — 작성자 메시지만 canViewReport=true 다.
   *
   * <p>서버는 리포트 전문(실행 상세·html)을 작업 작성자에게만 열어 준다. 받은 사람 메시지까지 true 면 화면이 「리포트 보기」를 그려 404 「리포트가
   * 없습니다」로 이어진다(WD-162 결함). 두 조회 경로(findByUserId·findUnreadByUserId)가 같은 값을 내는지도 본다.
   */
  @Test
  void canViewReport_trueOnlyForJobOwnerRecipient() {
    Long recipientId = createOtherUser("pmrepo_recipient");
    Long execId = createOwnedReportExecution();

    repository.create(userId, execId, "주간 리포트", Map.of("summary", "요약"), "REPORT");
    repository.create(recipientId, execId, "주간 리포트", Map.of("summary", "요약"), "REPORT");

    List<ProactiveMessageResponse> ownerMessages = repository.findByUserId(userId, 20, 0, false);
    List<ProactiveMessageResponse> recipientMessages =
        repository.findByUserId(recipientId, 20, 0, false);

    assertThat(ownerMessages)
        .singleElement()
        .extracting(ProactiveMessageResponse::canViewReport)
        .isEqualTo(true);
    assertThat(recipientMessages)
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.canViewReport()).isFalse();
              // 받은 사람에게도 요약과 작업·실행 참조는 그대로 간다 — 화면이 요약과 안내를 보일 수 있게
              assertThat(m.jobId()).isNotNull();
              assertThat(m.executionId()).isEqualTo(execId);
              assertThat(m.content()).containsEntry("summary", "요약");
            });

    assertThat(repository.findUnreadByUserId(userId))
        .singleElement()
        .extracting(ProactiveMessageResponse::canViewReport)
        .isEqualTo(true);
    assertThat(repository.findUnreadByUserId(recipientId))
        .singleElement()
        .extracting(ProactiveMessageResponse::canViewReport)
        .isEqualTo(false);
  }

  /** 실행에 연결되지 않은 메시지(LEFT JOIN 결과 작업 없음)는 열 리포트가 없으므로 false 다. */
  @Test
  void canViewReport_falseWhenMessageHasNoExecution() {
    repository.create(userId, null, "안내", Map.of(), "REPORT");

    assertThat(repository.findByUserId(userId, 20, 0, false))
        .singleElement()
        .extracting(ProactiveMessageResponse::canViewReport)
        .isEqualTo(false);
  }

  // -----------------------------------------------------------------------
  // 리포트 등급 가림 (WD-198)
  // -----------------------------------------------------------------------

  /** 사용자에게 지정 등급의 역할만 준다(ACTIVE 멤버십 포함) — 수신자의 「현재 최대 등급」. 테스트 트랜잭션과 함께 롤백된다. */
  private void giveLevel(Long uid, String levelName) {
    Long levelId =
        dsl.fetchValue(
                    "select id from security_level where tenant_id = ? and name = ?",
                    DEFAULT_TEST_TENANT_ID,
                    levelName)
                instanceof Number n
            ? n.longValue()
            : null;
    dsl.execute(
        "insert into membership(user_id, tenant_id, role, status) values (?, ?, 'MEMBER', 'ACTIVE')"
            + " on conflict (user_id, tenant_id) do nothing",
        uid,
        DEFAULT_TEST_TENANT_ID);
    Long roleId =
        ((Number)
                dsl.fetchValue(
                    "insert into role(tenant_id, name, description, is_system, max_security_level_id)"
                        + " values (?, ?, 'WD198', false, ?) returning id",
                    DEFAULT_TEST_TENANT_ID,
                    "pmrepo_lv_" + System.nanoTime(),
                    levelId))
            .longValue();
    dsl.execute(
        "insert into user_role(user_id, role_id, tenant_id) values (?, ?, ?)",
        uid,
        roleId,
        DEFAULT_TEST_TENANT_ID);
  }

  private void setReportLevel(Long execId, String levelName) {
    dsl.execute(
        "update proactive_job_execution set security_level_id ="
            + " (select id from security_level where tenant_id = ? and name = ?) where id = ?",
        DEFAULT_TEST_TENANT_ID,
        levelName,
        execId);
  }

  /**
   * 알림 메시지의 요약은 발송 때 리포트에서 복사해 둔 것이라 조회할 때마다 다시 나간다. 연결된 리포트의 등급이 수신자의 현재 등급보다 높으면 요약을 비우고 리포트 열기를
   * 막는다(작성자·다른 수신자 같은 기준). 두 조회 경로가 같다. 등급 없는 리포트의 메시지는 지금처럼 보인다.
   */
  @Test
  void reportLevelAboveRecipient_masksSummary_onBothQueries_nullLevelUnchanged() {
    Long recipientId = createOtherUser("pmrepo_lv_recipient");
    giveLevel(userId, "기밀");
    giveLevel(recipientId, "공개");
    Long leveled = createOwnedReportExecution();
    setReportLevel(leveled, "민감");
    Long legacy = createOwnedReportExecution();

    repository.create(userId, leveled, "민감 리포트", Map.of("summary", "민감 요약"), "REPORT");
    repository.create(recipientId, leveled, "민감 리포트", Map.of("summary", "민감 요약"), "REPORT");
    repository.create(recipientId, legacy, "이전 리포트", Map.of("summary", "이전 요약"), "REPORT");

    // 작성자(기밀) — 그대로
    ProactiveMessageResponse owner = repository.findByUserId(userId, 20, 0, false).get(0);
    assertThat(owner.content()).containsEntry("summary", "민감 요약");
    assertThat(owner.canViewReport()).isTrue();
    assertThat(owner.reportLevelBlocked()).isFalse();

    // 다른 수신자(공개) — 등급 리포트는 요약을 비우고, 등급 없는 리포트는 그대로
    for (List<ProactiveMessageResponse> list :
        List.of(
            repository.findByUserId(recipientId, 20, 0, false),
            repository.findUnreadByUserId(recipientId))) {
      ProactiveMessageResponse blocked =
          list.stream().filter(m -> leveled.equals(m.executionId())).findFirst().orElseThrow();
      assertThat(blocked.content()).doesNotContainKey("summary");
      assertThat(blocked.canViewReport()).isFalse();
      assertThat(blocked.reportLevelBlocked()).isTrue();
      ProactiveMessageResponse open =
          list.stream().filter(m -> legacy.equals(m.executionId())).findFirst().orElseThrow();
      assertThat(open.content()).containsEntry("summary", "이전 요약");
      assertThat(open.reportLevelBlocked()).isFalse();
    }

    // 작성자 등급을 낮추면 작성자 메시지도 가려지고 리포트 열기도 막힌다
    dsl.execute("delete from user_role where user_id = ?", userId);
    giveLevel(userId, "내부");
    ProactiveMessageResponse lowered = repository.findUnreadByUserId(userId).get(0);
    assertThat(lowered.content()).doesNotContainKey("summary");
    assertThat(lowered.canViewReport()).isFalse();
    assertThat(lowered.reportLevelBlocked()).isTrue();
  }
}
