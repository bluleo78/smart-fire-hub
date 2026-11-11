package com.smartfirehub.ai.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.ai.dto.AiSessionResponse;
import com.smartfirehub.ai.dto.CreateAiSessionRequest;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * AiSessionRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-712). 기존 Slack 컨텍스트 계약 테스트를 넓혀 인터페이스 공개 메서드 10개
 * 전부를 덮는다. ai_session 은 테넌트 정책 아래 있어 다른 테넌트 차단·세션 목록 페이지·정렬·빈 제목·JVM 시간대(KST)를 고정한다.
 */
public abstract class AiSessionRepositoryContractTest extends TenantPairContractBase {

  protected abstract AiSessionRepository repository();

  protected static final String TEAM_ID = "T_TEST_TEAM";
  protected static final String CHANNEL_ID = "C_TEST_CHANNEL";
  protected static final String THREAD_TS = "1700000001.000100";

  private long userA;
  private long otherUserA;

  @BeforeEach
  void setUpUsers() {
    userA = fx.createUser(prefix + "-u", null);
    otherUserA = fx.createUser(prefix + "-o", null);
  }

  /** 테넌트 A 에서 WEB 세션 하나를 만든다. */
  private AiSessionResponse web(long user, String sessionSuffix, String title) {
    return inA(
        () ->
            repository()
                .create(
                    user,
                    new CreateAiSessionRequest(prefix + "-" + sessionSuffix, null, null, title)));
  }

  private String sid(String suffix) {
    return prefix + "-" + suffix;
  }

  @Test
  @DisplayName("create → findById·findByUserIdAndSessionId 가 필드 그대로(한글 제목·시각) 돌려준다")
  void create_roundTrip() {
    AiSessionResponse created =
        inA(
            () ->
                repository()
                    .create(
                        userA,
                        new CreateAiSessionRequest(sid("rt"), "DATASET", 77L, "한글 제목 Title")));

    assertThat(created.id()).isNotNull();
    assertThat(created.userId()).isEqualTo(userA);
    assertThat(created.sessionId()).isEqualTo(sid("rt"));
    assertThat(created.contextType()).isEqualTo("DATASET");
    assertThat(created.contextResourceId()).isEqualTo(77L);
    assertThat(created.title()).isEqualTo("한글 제목 Title");
    assertThat(created.channelSource()).isEqualTo("WEB");
    assertThat(created.slackTeamId()).isNull();
    assertThat(created.createdAt()).isNotNull();
    assertThat(created.updatedAt()).isNotNull();

    assertThat(inA(() -> repository().findById(created.id()))).contains(created);
    assertThat(inA(() -> repository().findByUserIdAndSessionId(userA, sid("rt"))))
        .contains(created);
    assertThat(inA(() -> repository().findByUserIdAndSessionId(otherUserA, sid("rt")))).isEmpty();
    assertThat(inA(() -> repository().findById(-1L))).isEmpty();
    assertThat(fx.countTenantRows("ai_session", tenantA)).isEqualTo(1);
  }

  @Test
  @DisplayName("제목은 255자로 잘리고(WD-58), 빈 제목은 DB 별 기대(PG \"\" · Tibero null)")
  void title_truncated_andEmptyString() {
    AiSessionResponse longTitle = web(userA, "long", "가".repeat(300));
    assertThat(longTitle.title()).hasSize(255);

    AiSessionResponse empty = web(userA, "empty", "");
    if (emptyStringIsNull()) {
      assertThat(empty.title()).isNull();
    } else {
      assertThat(empty.title()).isEmpty();
    }
    AiSessionResponse nullTitle = web(userA, "nulltitle", null);
    assertThat(nullTitle.title()).isNull();
  }

  @Test
  @DisplayName("updateTitle 은 제목을 바꾸고 수정 시각을 올리며 255자로 자른다 — 빈 제목은 DB 별 기대")
  void updateTitle() {
    AiSessionResponse s = web(userA, "ut", "처음");
    LocalDateTime old = LocalDateTime.now().minusDays(1);
    fx.setAiSessionUpdatedAt(s.id(), old);

    runA(() -> repository().updateTitle(s.id(), "바뀐 제목"));
    AiSessionResponse after = inA(() -> repository().findById(s.id())).orElseThrow();
    assertThat(after.title()).isEqualTo("바뀐 제목");
    assertThat(after.updatedAt()).isAfter(old.plusHours(12));
    assertThat(after.createdAt()).isEqualTo(s.createdAt());

    runA(() -> repository().updateTitle(s.id(), "나".repeat(400)));
    assertThat(fx.aiSessionTitle(s.id())).hasSize(255);

    runA(() -> repository().updateTitle(s.id(), ""));
    if (emptyStringIsNull()) {
      assertThat(fx.aiSessionTitle(s.id())).isNull();
    } else {
      assertThat(fx.aiSessionTitle(s.id())).isEmpty();
    }
    // 없는 id 는 조용히 무시한다.
    runA(() -> repository().updateTitle(-1L, "x"));
  }

  @Test
  @DisplayName("delete 는 그 행만 지운다")
  void delete() {
    AiSessionResponse a = web(userA, "d1", "a");
    AiSessionResponse b = web(userA, "d2", "b");

    runA(() -> repository().delete(a.id()));

    assertThat(inA(() -> repository().findById(a.id()))).isEmpty();
    assertThat(inA(() -> repository().findById(b.id()))).isPresent();
    runA(() -> repository().delete(-1L));
  }

  @Test
  @DisplayName("findByUserId 는 본인 세션만 수정 시각 내림차순(같으면 id 내림차순)으로, page·size 로 자른다")
  void findByUserId_pageAndOrder() {
    AiSessionResponse s1 = web(userA, "p1", "1");
    AiSessionResponse s2 = web(userA, "p2", "2");
    AiSessionResponse s3 = web(userA, "p3", "3");
    AiSessionResponse s4 = web(userA, "p4", "4");
    AiSessionResponse s5 = web(userA, "p5", "5");
    web(otherUserA, "other", "남의 것");
    LocalDateTime base = LocalDateTime.now().minusDays(2).withNano(0);
    fx.setAiSessionUpdatedAt(s1.id(), base.plusHours(5));
    fx.setAiSessionUpdatedAt(s2.id(), base.plusHours(1));
    fx.setAiSessionUpdatedAt(s3.id(), base.plusHours(3));
    // s4·s5 는 같은 시각 — id 내림차순으로 s5 가 먼저다.
    fx.setAiSessionUpdatedAt(s4.id(), base.plusHours(2));
    fx.setAiSessionUpdatedAt(s5.id(), base.plusHours(2));

    List<Long> all = ids(inA(() -> repository().findByUserId(userA, 0, 10)));
    assertThat(all).containsExactly(s1.id(), s3.id(), s5.id(), s4.id(), s2.id());

    assertThat(ids(inA(() -> repository().findByUserId(userA, 0, 2))))
        .containsExactly(s1.id(), s3.id());
    assertThat(ids(inA(() -> repository().findByUserId(userA, 1, 2))))
        .containsExactly(s5.id(), s4.id());
    assertThat(ids(inA(() -> repository().findByUserId(userA, 2, 2)))).containsExactly(s2.id());
    assertThat(inA(() -> repository().findByUserId(userA, 3, 2))).isEmpty();
    assertThat(inA(() -> repository().findByUserId(-1L, 0, 10))).isEmpty();
  }

  private static List<Long> ids(List<AiSessionResponse> list) {
    return list.stream().map(AiSessionResponse::id).toList();
  }

  @Test
  @DisplayName("findByUserIdAndContext 는 사용자·컨텍스트 종류·리소스가 모두 같은 행만 돌려준다")
  void findByUserIdAndContext() {
    AiSessionResponse s =
        inA(
            () ->
                repository()
                    .create(userA, new CreateAiSessionRequest(sid("ctx"), "DATASET", 5L, "c")));

    assertThat(inA(() -> repository().findByUserIdAndContext(userA, "DATASET", 5L))).contains(s);
    assertThat(inA(() -> repository().findByUserIdAndContext(userA, "DATASET", 6L))).isEmpty();
    assertThat(inA(() -> repository().findByUserIdAndContext(userA, "PIPELINE", 5L))).isEmpty();
    assertThat(inA(() -> repository().findByUserIdAndContext(otherUserA, "DATASET", 5L))).isEmpty();
    // null 컨텍스트는 어떤 행과도 같지 않다(SQL = NULL).
    assertThat(inA(() -> repository().findByUserIdAndContext(userA, null, null))).isEmpty();
  }

  @Test
  @DisplayName("existsForOtherUser 는 같은 세션 ID 가 다른 사용자에게 있을 때만 true — 다른 테넌트는 보이지 않는다")
  void existsForOtherUser() {
    web(userA, "ex", "x");

    assertThat(inA(() -> repository().existsForOtherUser(otherUserA, sid("ex")))).isTrue();
    assertThat(inA(() -> repository().existsForOtherUser(userA, sid("ex")))).isFalse();
    assertThat(inA(() -> repository().existsForOtherUser(userA, sid("none")))).isFalse();
    // 테넌트 B 에서는 A 의 행이 정책에 가려져 다른 사용자 것으로도 보이지 않는다.
    assertThat(inB(() -> repository().existsForOtherUser(otherUserA, sid("ex")))).isFalse();
  }

  @Test
  @DisplayName("다른 테넌트 차단 — B 컨텍스트에서는 A 의 세션이 조회·수정·삭제 어디에도 닿지 않는다")
  void otherTenantIsBlocked() {
    AiSessionResponse s = web(userA, "blk", "원래");
    inA(
        () ->
            repository()
                .createSlackSession(userA, sid("blk-slack"), TEAM_ID, CHANNEL_ID, THREAD_TS, "슬랙"));

    assertThat(inB(() -> repository().findById(s.id()))).isEmpty();
    assertThat(inB(() -> repository().findByUserIdAndSessionId(userA, sid("blk")))).isEmpty();
    assertThat(inB(() -> repository().findByUserId(userA, 0, 10))).isEmpty();
    assertThat(inB(() -> repository().findBySlackContext(TEAM_ID, CHANNEL_ID, THREAD_TS)))
        .isEmpty();
    inB(
        () -> {
          repository().updateTitle(s.id(), "침입");
          repository().delete(s.id());
          return null;
        });
    // A 쪽은 그대로다.
    assertThat(inA(() -> repository().findById(s.id())).orElseThrow().title()).isEqualTo("원래");
    assertThat(inA(() -> repository().findBySlackContext(TEAM_ID, CHANNEL_ID, THREAD_TS)))
        .isPresent();
  }

  // ---- Slack 컨텍스트(기존 계약) ----

  @Test
  @DisplayName("createSlackSession → findBySlackContext 동일 row 복구")
  void createSlackSession_thenFindBySlackContext_returnsCreatedRow() {
    long createdId =
        inA(
            () ->
                repository()
                    .createSlackSession(
                        userA, "agent-" + prefix, TEAM_ID, CHANNEL_ID, THREAD_TS, "Slack 대화 테스트"));

    AiSessionResponse session =
        inA(() -> repository().findBySlackContext(TEAM_ID, CHANNEL_ID, THREAD_TS)).orElseThrow();
    assertThat(session.id()).isEqualTo(createdId);
    assertThat(session.userId()).isEqualTo(userA);
    assertThat(session.sessionId()).isEqualTo("agent-" + prefix);
    assertThat(session.channelSource()).isEqualTo("SLACK");
    assertThat(session.slackTeamId()).isEqualTo(TEAM_ID);
    assertThat(session.slackChannelId()).isEqualTo(CHANNEL_ID);
    assertThat(session.slackThreadTs()).isEqualTo(THREAD_TS);
    assertThat(session.title()).isEqualTo("Slack 대화 테스트");
    assertThat(session.createdAt()).isNotNull();
    // jOOQ 와 같이 id 조회(WEB 팩토리)는 채널 정보를 WEB·null 로 돌려준다.
    AiSessionResponse byId = inA(() -> repository().findById(createdId)).orElseThrow();
    assertThat(byId.channelSource()).isEqualTo("WEB");
    assertThat(byId.slackTeamId()).isNull();
  }

  @Test
  @DisplayName("WEB 세션은 findBySlackContext 조회 미포함")
  void findBySlackContext_webSession_notReturned() {
    web(userA, "webonly", "웹 세션");

    assertThat(inA(() -> repository().findBySlackContext(TEAM_ID, CHANNEL_ID, THREAD_TS)))
        .isEmpty();
  }

  @Test
  @DisplayName("서로 다른 thread_ts → 독립적인 별개 세션")
  void createSlackSession_differentThreadTs_independentSessions() {
    String threadTs2 = "1700000002.000200";
    long id1 =
        inA(
            () ->
                repository()
                    .createSlackSession(userA, sid("t1"), TEAM_ID, CHANNEL_ID, THREAD_TS, "스레드1"));
    long id2 =
        inA(
            () ->
                repository()
                    .createSlackSession(userA, sid("t2"), TEAM_ID, CHANNEL_ID, threadTs2, "스레드2"));

    assertThat(inA(() -> repository().findBySlackContext(TEAM_ID, CHANNEL_ID, THREAD_TS)))
        .get()
        .extracting(AiSessionResponse::id)
        .isEqualTo(id1);
    assertThat(inA(() -> repository().findBySlackContext(TEAM_ID, CHANNEL_ID, threadTs2)))
        .get()
        .extracting(AiSessionResponse::id)
        .isEqualTo(id2);
  }

  @Test
  @DisplayName("중복 (team,channel,thread) INSERT → 두 DB 모두 DuplicateKeyException")
  void createSlackSession_duplicateThread_throwsDuplicateKey() {
    inA(
        () ->
            repository()
                .createSlackSession(userA, sid("dup1"), TEAM_ID, CHANNEL_ID, THREAD_TS, "첫 번째"));

    assertThatThrownBy(
            () ->
                inA(
                    () ->
                        repository()
                            .createSlackSession(
                                userA, sid("dup2"), TEAM_ID, CHANNEL_ID, THREAD_TS, "두 번째")))
        .isInstanceOf(DuplicateKeyException.class);
  }

  // ---- JVM 시간대 ----

  /**
   * 이 구현이 created_at·updated_at 에 쓰는 「지금」 벽시계의 기대값. 기본은 호출 시점 JVM 기본 시간대의 {@link
   * LocalDateTime#now()} — MyBatis 구현(PG·Tibero)은 이 값을 Java 에서 바인드한다. jOOQ 하위는 PG 세션 시간대 기준으로
   * 덮어쓴다(그쪽 javadoc).
   */
  protected LocalDateTime expectedWriteClockNow() {
    return LocalDateTime.now();
  }

  @Test
  @DisplayName("JVM 기본 시간대가 KST 면 세션 시각은 KST 벽시계로 쓰이고(UTC 로 쓰면 9시간 어긋나 실패), 그대로 읽힌다")
  void timestamps_followJvmLocalClock() {
    TimeZone original = TimeZone.getDefault();
    try {
      // Tibero 실서버 태스크(tiberoTest)는 JVM 을 UTC 로 띄운다 — KST 로 바꿔야 「UTC 벽시계(dbNow)로 쓰는」 결함이 9시간 차이로
      // 드러난다(실측 RED: 8H59M59S). PG 태스크는 컴퓨터 시간대로 뜨고 풀 연결의 세션 시간대가 접속 때 JVM 값에 고정되므로, PG 에서
      // DB 시계로 썼을 때 이 단언이 실패하는지는 컴퓨터 시간대에 달렸다 — 결함 검출의 기준은 Tibero 하위다.
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
      LocalDateTime expectedCreate = expectedWriteClockNow();
      AiSessionResponse kstCreated = web(userA, "tzk", "kst");
      assertThat(Duration.between(expectedCreate, kstCreated.createdAt()).abs())
          .isLessThan(Duration.ofMinutes(1));

      runA(() -> repository().updateTitle(kstCreated.id(), "kst2"));
      LocalDateTime expectedUpdate = expectedWriteClockNow();
      AiSessionResponse updated = inA(() -> repository().findById(kstCreated.id())).orElseThrow();
      assertThat(Duration.between(expectedUpdate, updated.updatedAt()).abs())
          .isLessThan(Duration.ofMinutes(1));

      // 읽기는 변환 없이 그대로 — JVM 시간대를 UTC 로 돌려 다시 읽어도 같은 값이다.
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      AiSessionResponse utcRead = inA(() -> repository().findById(kstCreated.id())).orElseThrow();
      assertThat(utcRead.createdAt()).isEqualTo(kstCreated.createdAt());
      assertThat(utcRead.updatedAt()).isEqualTo(updated.updatedAt());
    } finally {
      TimeZone.setDefault(original);
    }
  }
}
