package com.smartfirehub.global.platformdb.mybatis;

/**
 * INSERT 파라미터 겸 생성 키 홀더의 공통 부모. Tibero 에는 RETURNING 이 없어 jOOQ 의 {@code returning(ID)} 대신 JDBC 생성 키
 * ({@code useGeneratedKeys}, {@code keyProperty="id"}) 가 {@code id} 에 써 넣는다. record 는 불변이라 생성 키를 받을
 * 수 없어, 각 INSERT 파라미터 클래스가 이 클래스를 상속한다.
 */
public abstract class GeneratedId {

  private Long id;

  /** 넣은 뒤 채워진 생성 키. */
  public Long getId() {
    return id;
  }

  /** 생성 키 — 넣은 뒤 MyBatis 가 채운다. */
  public void setId(Long id) {
    this.id = id;
  }
}
