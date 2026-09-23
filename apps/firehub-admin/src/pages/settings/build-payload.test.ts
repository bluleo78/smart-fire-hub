import { describe, expect, it } from 'vitest';

import { buildSettingsPayload } from './build-payload';

const ORIGINAL = {
  'embedding.provider': 'OLLAMA',
  'embedding.model': 'bge-m3',
  'embedding.base_url': 'http://localhost:11434',
  'embedding.api_key': '',
};

describe('buildSettingsPayload', () => {
  it('변경되지 않은 키는 보내지 않는다', () => {
    const { payload } = buildSettingsPayload({ ...ORIGINAL }, ORIGINAL, new Set());
    expect(payload).toEqual({});
  });

  it('바뀐 비-비밀 키만 담는다', () => {
    const { payload, diff } = buildSettingsPayload(
      { ...ORIGINAL, 'embedding.model': 'bge-m4' },
      ORIGINAL,
      new Set(),
    );
    expect(payload).toEqual({ 'embedding.model': 'bge-m4' });
    expect(diff).toEqual([
      { key: 'embedding.model', label: '모델', before: 'bge-m3', after: 'bge-m4', secret: false },
    ]);
  });

  it('비밀 키를 빈 채로 두면 페이로드에 넣지 않는다 (= 유지) — 양성 대조군 포함', () => {
    // 비밀 키는 비워 두고, 비-비밀 키(embedding.model)는 바꾼다. payload 가 그냥 텅 비어서
    // 통과하는 게 아니라 "바꾼 키는 담기고 빈 채로 둔 비밀 키는 안 담긴다"를 같은 객체로
    // 단언해야 이 테스트가 실제로 무언가를 검사한다는 것이 증명된다(양성 대조군).
    const { payload } = buildSettingsPayload(
      { ...ORIGINAL, 'embedding.api_key': '', 'embedding.model': 'bge-m4' },
      ORIGINAL,
      new Set(),
    );
    expect(payload).toEqual({ 'embedding.model': 'bge-m4' });
    expect(payload).not.toHaveProperty('embedding.api_key');
  });

  it('비밀 키에 새 값을 넣으면 그대로 담고 diff 는 값을 숨긴다', () => {
    const { payload, diff } = buildSettingsPayload(
      { ...ORIGINAL, 'embedding.api_key': 'n3wEmbeddingKey' },
      ORIGINAL,
      new Set(),
    );
    expect(payload).toEqual({ 'embedding.api_key': 'n3wEmbeddingKey' });
    expect(diff).toEqual([
      { key: 'embedding.api_key', label: 'API 키', before: '', after: '값 변경됨', secret: true },
    ]);
  });

  it('지우기 표시된 비밀 키는 빈 문자열을 명시적으로 보낸다', () => {
    const { payload, diff } = buildSettingsPayload(
      { ...ORIGINAL, 'embedding.api_key': '' },
      ORIGINAL,
      new Set(['embedding.api_key']),
    );
    expect(payload).toEqual({ 'embedding.api_key': '' });
    expect(diff[0]).toMatchObject({ key: 'embedding.api_key', after: '값 삭제됨', secret: true });
  });

  it('서버가 준 마스크 값이 폼에 들어와 있어도 그 키를 되돌려보내지 않는다', () => {
    // 진짜 위험은 "빌더가 마스크를 만든다"가 아니라(그럴 코드 경로가 없다)
    // "서버가 준 마스크가 폼 값으로 들어와 그대로 되돌아간다" 쪽이다. 그러면 서버
    // isMaskSentinel(`****` 접두 + 길이 4 또는 8)이 그 키를 드롭해 "204 성공 + 아무 일도 없음"이
    // 되거나, 길이가 어긋나면 마스크 문자열 자체가 진짜 비밀값으로 저장된다.
    // 비-비밀 키(embedding.model)를 함께 바꿔 payload 가 애초에 비어 있어서 통과하는 게 아님을
    // 보장한다(양성 대조군).
    const { payload, diff } = buildSettingsPayload(
      { ...ORIGINAL, 'embedding.api_key': '****ab12', 'embedding.model': 'bge-m4' },
      ORIGINAL,
      new Set(),
    );
    expect(payload).toEqual({ 'embedding.model': 'bge-m4' });
    expect(payload).not.toHaveProperty('embedding.api_key');
    expect(Object.values(payload).some((v) => v.startsWith('****'))).toBe(false);
    expect(diff.some((d) => d.key === 'embedding.api_key')).toBe(false);
  });

  it('카탈로그에 없는 키(smtp.*)는 폼에 섞여 있어도 페이로드에 싣지 않는다 (#712)', () => {
    // 서버는 smtp.* 가 하나라도 섞인 플랫폼 쓰기를 요청 전체 400 으로 거부한다. 폼이 카탈로그
    // 밖 키를 들고 있더라도 빌더는 카탈로그 키만 순회해야 한다. embedding.model 변경은 양성 대조군.
    const { payload } = buildSettingsPayload(
      { ...ORIGINAL, 'smtp.host': 'mail.example.org', 'embedding.model': 'bge-m4' },
      { ...ORIGINAL, 'smtp.host': 'smtp.example.com' },
      new Set(),
    );
    expect(payload).toEqual({ 'embedding.model': 'bge-m4' });
  });
});
