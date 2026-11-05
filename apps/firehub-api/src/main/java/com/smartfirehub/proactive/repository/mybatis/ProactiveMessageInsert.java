package com.smartfirehub.proactive.repository.mybatis;

/**
 * INSERT 파라미터 겸 생성 키 홀더(WD-487 PoC).
 *
 * <p>{@code useGeneratedKeys} 는 생성된 키를 파라미터 객체의 프로퍼티에 써 넣으므로, 입력 값과 {@link #getId()} 를 한 객체에 둔다.
 * useGeneratedKeys 는 JDBC 표준이라 PG·Tibero 모두 같은 방식으로 쓸 수 있다.
 */
public class ProactiveMessageInsert {

  private final Long userId;
  private final Long executionId;
  private final String title;
  private final String contentJson;
  private final String messageType;

  private Long id;

  public ProactiveMessageInsert(
      Long userId, Long executionId, String title, String contentJson, String messageType) {
    this.userId = userId;
    this.executionId = executionId;
    this.title = title;
    this.contentJson = contentJson;
    this.messageType = messageType;
  }

  public Long getUserId() {
    return userId;
  }

  public Long getExecutionId() {
    return executionId;
  }

  public String getTitle() {
    return title;
  }

  public String getContentJson() {
    return contentJson;
  }

  public String getMessageType() {
    return messageType;
  }

  /** INSERT 후 MyBatis 가 채우는 생성 id. */
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }
}
