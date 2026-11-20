import { describe, expect, it } from 'vitest';

import { permissionCategoryLabel } from './permission-category';

describe('permissionCategoryLabel', () => {
  it.each([
    ['user', '사용자'],
    ['role', '역할'],
    ['permission', '권한'],
    ['dataset', '데이터셋'],
    ['data', '데이터'],
    ['pipeline', '파이프라인'],
    ['trigger', '트리거'],
    ['apiconnection', 'API 연결'],
    ['analytics', '분석'],
    ['ai', 'AI'],
    ['chat', '채팅'],
    ['settings', '설정'],
    ['security', '보안'],
    ['proactive', '스마트 작업'],
    ['notification', '알림'],
    ['audit', '감사 로그'],
    ['ontology', '지식 모델'],
    ['platform', '플랫폼'],
  ])('%s -> %s', (key, label) => {
    expect(permissionCategoryLabel(key)).toBe(label);
  });

  it.each(['ontology2', '__proto__', 'constructor', 'DATASET'])(
    '맵에 없는 키 %s 는 원본 그대로 돌려준다',
    (key) => {
      expect(permissionCategoryLabel(key)).toBe(key);
    },
  );
});
