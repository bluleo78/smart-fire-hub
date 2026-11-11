package com.smartfirehub.user.repository.mybatis;

/**
 * 사용자 INSERT 파라미터 겸 생성 키 홀더(WD-706). {@code useGeneratedKeys} 가 생성된 id 를 이 객체의 {@code id} 에 써 넣는다 —
 * PG·Tibero 모두 JDBC 표준 경로라 RETURNING 이 없는 Tibero 도 같은 방식이다.
 */
public class UserInsert {

  private final String username;
  private final String email;
  private final String password;
  private final String name;
  private final boolean mustChangePassword;

  private Long id;

  public UserInsert(
      String username, String email, String password, String name, boolean mustChangePassword) {
    this.username = username;
    this.email = email;
    this.password = password;
    this.name = name;
    this.mustChangePassword = mustChangePassword;
  }

  public String getUsername() {
    return username;
  }

  public String getEmail() {
    return email;
  }

  public String getPassword() {
    return password;
  }

  public String getName() {
    return name;
  }

  public boolean isMustChangePassword() {
    return mustChangePassword;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }
}
