package com.smartfirehub.ai.repository.mybatis;

import java.time.LocalDateTime;

/**
 * ai_session INSERT 파라미터 겸 생성 키 홀더(WD-712). {@code useGeneratedKeys} 가 생성된 id 를 {@code id} 에 써 넣는다
 * — RETURNING 이 없는 Tibero 도 같은 JDBC 표준 경로다. WEB 삽입은 Slack 필드를 쓰지 않는다.
 *
 * <p>왜 getter 가 없나: MyBatis Reflector 는 getter·setter 없는 필드도 직접 읽고 쓴다 — 매퍼 XML 의 {@code #{userId}}
 * 등은 private final 필드를 읽고, 생성 키는 {@code id} 필드에 바로 쓴다(keyProperty="id"). record 는 불변이라 생성 키를 받을 수
 * 없어 이 최소 클래스를 쓴다.
 */
public class AiSessionInsert {

  private final Long userId;
  private final String sessionId;
  private final String contextType;
  private final Long contextResourceId;
  private final String title;
  private final String slackTeamId;
  private final String slackChannelId;
  private final String slackThreadTs;

  /** created_at·updated_at 에 넣을 JVM 시간대 벽시계(jOOQ 의 LOCALTIMESTAMP 와 같은 기준 — 리포지토리 javadoc). */
  private final LocalDateTime now;

  /** 생성 키 — INSERT 뒤 MyBatis 가 필드에 직접 채운다. */
  private Long id;

  /** INSERT 에 바인드할 값으로 홀더를 만든다 — id 는 INSERT 뒤 생성 키로 채워진다. */
  public AiSessionInsert(
      Long userId,
      String sessionId,
      String contextType,
      Long contextResourceId,
      String title,
      String slackTeamId,
      String slackChannelId,
      String slackThreadTs,
      LocalDateTime now) {
    this.userId = userId;
    this.sessionId = sessionId;
    this.contextType = contextType;
    this.contextResourceId = contextResourceId;
    this.title = title;
    this.slackTeamId = slackTeamId;
    this.slackChannelId = slackChannelId;
    this.slackThreadTs = slackThreadTs;
    this.now = now;
  }

  /** INSERT 뒤 채워진 생성 키. */
  public Long getId() {
    return id;
  }
}
