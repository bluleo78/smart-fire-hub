/**
 * 대시보드/홈 도메인 모킹 데이터 팩토리
 * src/types/dashboard.ts 타입 기반으로 테스트용 활동 피드 객체를 생성한다.
 * overrides 파라미터로 특정 필드만 덮어쓸 수 있다.
 */

import type { ActivityFeedResponse, ActivityItem } from '@/types/dashboard';

/** 활동 피드 항목 생성 */
export function createActivityItem(overrides?: Partial<ActivityItem>): ActivityItem {
  return {
    id: 1,
    eventType: 'DATASET_CREATED',
    title: '데이터셋 생성 항목',
    description: 'desc',
    severity: 'INFO',
    entityType: 'DATASET',
    entityId: 1,
    occurredAt: '2026-05-15T10:00:00',
    isResolved: true,
    ...overrides,
  };
}

/** 활동 피드 응답 생성 — totalCount 는 items 길이로 맞춘다 */
export function createActivityFeedResponse(
  items: ActivityItem[] = [createActivityItem()],
  overrides?: Partial<ActivityFeedResponse>,
): ActivityFeedResponse {
  return { items, totalCount: items.length, hasMore: false, ...overrides };
}
