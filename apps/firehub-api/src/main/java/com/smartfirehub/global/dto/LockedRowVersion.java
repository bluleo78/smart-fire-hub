package com.smartfirehub.global.dto;

import java.time.LocalDateTime;

/**
 * 행 잠금({@code FOR UPDATE})을 잡고 읽은 수정 시각 — 낙관적 잠금(WD-364)의 편집 기준 버전 비교에 쓴다.
 *
 * <p><b>왜 {@code Optional<LocalDateTime>} 이 아니라 감싼 값인가(WD-485)</b>: 리포지토리는 「행 없음」(빈 Optional)과 「행은
 * 있는데 수정 시각이 비어 있음」(nullable 컬럼, V42 — 이 값의 {@code updatedAt} 이 null)을 구분해 돌려줘야 한다. {@code
 * Optional<LocalDateTime>} 은 null 을 담을 수 없어 둘이 섞이고, 수정 시각이 빈 옛 행을 "없음"(404)으로 잘못 거부하게 된다. 예전에는 jOOQ
 * {@code Record1} 이 이 구분을 해 주었는데, 서비스가 jOOQ 타입에 기대지 않도록 도메인 값으로 바꿨다.
 *
 * @param updatedAt 잠근 행의 수정 시각(비어 있으면 null)
 */
public record LockedRowVersion(LocalDateTime updatedAt) {}
