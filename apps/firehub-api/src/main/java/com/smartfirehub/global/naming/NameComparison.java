package com.smartfirehub.global.naming;

import com.smartfirehub.global.platformdb.mybatis.MyBatisPlatformConfig;

/** 개명 판정에 쓰는 이름 비교 기준(WD-1108). 자원마다 중복 검사 SQL 의 trim 범위가 달라, 개명 판정이 그 SQL 과 같은 기준을 써야 한다. */
public enum NameComparison {
  /** SQL {@code lower(trim(name))} — 앞뒤 ASCII 공백(U+0020)만 걷는다(WD-1085). */
  ASCII_TRIM,
  /**
   * 유니코드 공백(전각·NBSP 등)을 ASCII 공백으로 바꾼 뒤 앞뒤 ASCII 공백(U+0020)만 걷는 PostgreSQL SQL {@code
   * lower(trim(translate(x)))} — 스마트 작업(WD-1108). U+001F 같은 제어 문자는 걷지 않는다.
   */
  UNICODE_TRIM;

  /**
   * MyBatis databaseId 로 이 설치본의 이름 비교 기준을 정한다(WD-1154). databaseId 는 매퍼가 {@code
   * countByOwnerAndName} 판(postgresql·공통)을 고르는 값과 같아, 개명 판정 기준이 SQL 과 항상 같은 출처에서 나온다. postgresql 판만
   * 유니코드 공백 변환 SQL 이라 UNICODE_TRIM, 그 밖(tibero·null 등 공통 문)은 ASCII_TRIM.
   */
  public static NameComparison forDatabaseId(String databaseId) {
    return MyBatisPlatformConfig.POSTGRESQL.equals(databaseId) ? UNICODE_TRIM : ASCII_TRIM;
  }
}
