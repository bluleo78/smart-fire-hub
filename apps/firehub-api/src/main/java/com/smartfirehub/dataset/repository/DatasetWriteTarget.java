package com.smartfirehub.dataset.repository;

/**
 * 데이터 테이블 쓰기 직전에 데이터셋 행 잠금과 같은 문장으로 읽는 최소 정보(WD-666).
 *
 * <p>왜 따로 두나: {@code findById} 는 카테고리·태그·등급까지 여러 번 읽어 잠금과 한 문장으로 묶을 수 없다. 쓰기 경로가 실제로 쓰는 값은 잠그는 행
 * 자신의 열(이름·테이블 이름·저장 유형)뿐이라, 잠금 문장에서 바로 읽으면 왕복이 한 번으로 줄고 값도 잠금 뒤의 최신이다.
 *
 * @param id 데이터셋 id
 * @param name 데이터셋 이름(감사·알림 문구용)
 * @param tableName 데이터 테이블 이름
 * @param storageType 저장 유형(TABLE·DOCUMENT·FILE)
 */
public record DatasetWriteTarget(Long id, String name, String tableName, String storageType) {}
