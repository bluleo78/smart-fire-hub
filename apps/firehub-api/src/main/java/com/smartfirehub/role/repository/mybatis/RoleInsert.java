package com.smartfirehub.role.repository.mybatis;

/** 역할 INSERT 파라미터 겸 생성 키 홀더(WD-706) — {@code useGeneratedKeys} 가 id 를 채운다. */
public class RoleInsert {

  private final String name;
  private final String description;

  private Long id;

  public RoleInsert(String name, String description) {
    this.name = name;
    this.description = description;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }
}
