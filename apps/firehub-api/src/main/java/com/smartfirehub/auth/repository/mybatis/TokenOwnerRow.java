package com.smartfirehub.auth.repository.mybatis;

/**
 * {@code RefreshTokenMapper#selectOwner} 결과 행(WD-1176). MyBatis 가 열 별칭(familyId·userId·plane)을 대소문자 구분 없이 세터로 채운다 —
 * Tibero 가 별칭을 대문자로 돌려줘도 같은 매핑이 동작하게 하려는 가변 POJO 다. 저장소 밖으로는 나가지 않는다.
 */
public class TokenOwnerRow {

  private String familyId;
  private Long userId;
  private String plane;

  public String getFamilyId() {
    return familyId;
  }

  public void setFamilyId(String familyId) {
    this.familyId = familyId;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getPlane() {
    return plane;
  }

  public void setPlane(String plane) {
    this.plane = plane;
  }
}
