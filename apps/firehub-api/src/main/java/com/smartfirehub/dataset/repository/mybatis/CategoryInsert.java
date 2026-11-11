package com.smartfirehub.dataset.repository.mybatis;

/**
 * 데이터셋 카테고리 INSERT 파라미터 겸 생성 키 홀더(WD-707). {@code useGeneratedKeys} 가 생성된 id 를 이 객체의 {@code id} 에 써
 * 넣는다 — RETURNING 이 없는 Tibero 도 JDBC 표준 경로로 같은 방식이다.
 */
public class CategoryInsert {

  private final String name;
  private final String description;

  private Long id;

  public CategoryInsert(String name, String description) {
    this.name = name;
    this.description = description;
  }

  /** 매퍼 XML 의 #{name} 바인딩용 접근자. */
  public String getName() {
    return name;
  }

  /** 매퍼 XML 의 #{description} 바인딩용 접근자. */
  public String getDescription() {
    return description;
  }

  /** 넣은 뒤 채워진 생성 키. */
  public Long getId() {
    return id;
  }

  /** 생성 키 — 넣은 뒤 MyBatis 가 채운다(Tibero 는 RETURNING 이 없다). */
  public void setId(Long id) {
    this.id = id;
  }
}
