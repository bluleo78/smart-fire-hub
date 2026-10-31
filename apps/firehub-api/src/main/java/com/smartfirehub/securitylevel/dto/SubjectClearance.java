package com.smartfirehub.securitylevel.dto;

import com.smartfirehub.securitylevel.access.LevelPolicy;

/**
 * 허용 목록 대상(역할·사용자)의 열람 등급 요약(WD-292). 역할은 자기 max_security_level, 사용자는 보유 역할 등급의 최댓값(유효 열람 등급,
 * {@link com.smartfirehub.securitylevel.access.ClearanceResolver} 와 같은 기준)이다.
 *
 * <p>왜 필요한가: 열람 판정은 「열람 등급 rank ≥ 데이터셋 등급 rank」를 허용 목록보다 먼저 본다({@link
 * com.smartfirehub.securitylevel.access.DatasetAccessPolicy#decide}). 웹이 대상의 등급을 보여 줘야 등급이 모자라 허용
 * 목록에 넣어도 효과가 없는 대상을 알아챌 수 있다.
 */
public record SubjectClearance(Long levelId, String levelName, int rank) {

  /** 등급 정책 스냅샷 → 응답용 요약. */
  public static SubjectClearance of(LevelPolicy p) {
    return new SubjectClearance(p.id(), p.name(), p.rank());
  }
}
