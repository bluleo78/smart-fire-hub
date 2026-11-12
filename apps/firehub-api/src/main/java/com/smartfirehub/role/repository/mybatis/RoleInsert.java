package com.smartfirehub.role.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.GeneratedId;

/** 역할 INSERT 파라미터 겸 생성 키 홀더(WD-706) — {@code useGeneratedKeys} 가 id 를 채운다. */
public class RoleInsert extends GeneratedId {

  private final String name;
  private final String description;

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
}
