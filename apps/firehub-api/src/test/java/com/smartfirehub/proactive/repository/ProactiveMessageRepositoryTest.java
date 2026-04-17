package com.smartfirehub.proactive.repository;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.proactive.dto.ProactiveMessageResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProactiveMessageRepository 통합 테스트.
 * 실제 DB를 사용해 create/findUnread/countUnread/markAsRead/markAllAsRead/findByUserId 메서드를 커버한다.
 */
@Transactional
class ProactiveMessageRepositoryTest extends IntegrationTestBase {

  @Autowired private ProactiveMessageRepository repository;
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
    assertThat(unread).extracting(ProactiveMessageResponse::title)
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

    List<ProactiveMessageResponse> all = repository.findByUserId(userId, 10, 0);
    ProactiveMessageResponse msg = all.stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    assertThat(msg.read()).isTrue();
    assertThat(msg.readAt()).isNotNull();
  }

  @Test
  void markAsRead_wrongUser_doesNotMarkRead() {
    Long id = repository.create(userId, null, "Mine", Map.of(), "REPORT");

    // 다른 userId로 markAsRead 호출 → where 조건 불일치로 업데이트 안 됨
    repository.markAsRead(id, userId + 9999L);

    int count = repository.countUnreadByUserId(userId);
    assertThat(count).isEqualTo(1);  // 여전히 unread
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
    List<ProactiveMessageResponse> page1 = repository.findByUserId(userId, 2, 0);
    assertThat(page1).hasSize(2);

    // limit=2, offset=2 → 나머지 1개
    List<ProactiveMessageResponse> page2 = repository.findByUserId(userId, 2, 2);
    assertThat(page2).hasSize(1);
  }

  @Test
  void findByUserId_includesReadAndUnreadMessages() {
    Long id1 = repository.create(userId, null, "Unread", Map.of(), "REPORT");
    Long id2 = repository.create(userId, null, "Read", Map.of(), "REPORT");

    repository.markAsRead(id2, userId);

    List<ProactiveMessageResponse> all = repository.findByUserId(userId, 10, 0);
    assertThat(all).hasSize(2);

    // 읽은 것과 안 읽은 것 모두 포함
    assertThat(all).anyMatch(m -> m.id().equals(id1) && !m.read());
    assertThat(all).anyMatch(m -> m.id().equals(id2) && m.read());
  }

  @Test
  void findByUserId_contentDeserialized_correctly() {
    Map<String, Object> content = Map.of("summary", "test summary", "count", 42);
    Long id = repository.create(userId, null, "Rich Content", content, "REPORT");

    List<ProactiveMessageResponse> messages = repository.findByUserId(userId, 10, 0);
    ProactiveMessageResponse msg = messages.stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();

    assertThat(msg.content()).containsKey("summary");
    assertThat(msg.content().get("summary")).isEqualTo("test summary");
  }
}
