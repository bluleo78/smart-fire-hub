package com.smartfirehub.global.platformdb;

/**
 * DB 가 보고한 구조화 오류 — 벤더 드라이버 타입(PG 의 ServerErrorMessage 등)을 호출자에게 새지 않게 하려고 필요한 필드만 옮긴 값이다.
 *
 * @param sqlState SQLState (예: 23505)
 * @param constraint 위반된 제약 이름(없으면 null)
 * @param column 관련 열 이름(없으면 null)
 * @param message 서버 주 메시지(없으면 null) — 판별에만 쓰고 사용자 응답에는 싣지 않는다
 * @param detail 서버 세부 메시지(없으면 null) — 판별에만 쓰고 사용자 응답에는 싣지 않는다
 */
public record DbErrorDetail(
    String sqlState, String constraint, String column, String message, String detail) {}
