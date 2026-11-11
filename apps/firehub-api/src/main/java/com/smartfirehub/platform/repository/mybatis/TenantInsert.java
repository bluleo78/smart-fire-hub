package com.smartfirehub.platform.repository.mybatis;

/** 테넌트 INSERT 파라미터 겸 생성 키 홀더(WD-706) — {@code useGeneratedKeys} 가 id 를 채운다. */
public class TenantInsert {

  private final String slug;
  private final String name;

  private Long id;

  public TenantInsert(String slug, String name) {
    this.slug = slug;
    this.name = name;
  }

  public String getSlug() {
    return slug;
  }

  public String getName() {
    return name;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }
}
