import { describe, expect, it } from 'vitest';

import { buildSettingsPayload } from './build-payload';

const ORIGINAL = {
  'ai.model': 'claude-sonnet-5',
  'ai.max_turns': '20',
  'smtp.starttls': 'true',
  'smtp.password': '',
  'ai.api_key': '',
  'ai.cli_oauth_token': '',
};

describe('buildSettingsPayload', () => {
  it('변경되지 않은 키는 보내지 않는다 — 전부 보내면 테넌트 상속이 끊긴다', () => {
    const { payload } = buildSettingsPayload({ ...ORIGINAL }, ORIGINAL, new Set());
    expect(payload).toEqual({});
  });

  it('바뀐 비-비밀 키만 담는다', () => {
    const { payload, diff } = buildSettingsPayload(
      { ...ORIGINAL, 'ai.max_turns': '30' },
      ORIGINAL,
      new Set(),
    );
    expect(payload).toEqual({ 'ai.max_turns': '30' });
    expect(diff).toEqual([
      { key: 'ai.max_turns', label: '최대 턴 수', group: 'AI 에이전트', before: '20', after: '30', secret: false },
    ]);
  });

  it('비밀 키를 빈 채로 두면 페이로드에 넣지 않는다 (= 유지) — 양성 대조군 포함', () => {
    // smtp.password 는 비워 두고, 다른 비밀 키(ai.cli_oauth_token)에는 진짜 새 값을 넣는다.
    // 둘 다 같은 "비밀 분기" 코드를 지나므로, payload 가 그냥 텅 비어서 통과하는 게 아니라
    // "새 값을 넣은 비밀 키는 담기고 빈 채로 둔 비밀 키는 안 담긴다"를 같은 객체로 단언해야
    // 이 테스트가 실제로 무언가를 검사한다는 것이 증명된다(양성 대조군).
    const { payload } = buildSettingsPayload(
      { ...ORIGINAL, 'smtp.password': '', 'ai.cli_oauth_token': 'n3wT0ken' },
      ORIGINAL,
      new Set(),
    );
    expect(payload).toEqual({ 'ai.cli_oauth_token': 'n3wT0ken' });
    expect(payload).not.toHaveProperty('smtp.password');
  });

  it('비밀 키에 새 값을 넣으면 그대로 담고 diff 는 값을 숨긴다', () => {
    const { payload, diff } = buildSettingsPayload(
      { ...ORIGINAL, 'smtp.password': 'n3wPassw0rd' },
      ORIGINAL,
      new Set(),
    );
    expect(payload).toEqual({ 'smtp.password': 'n3wPassw0rd' });
    expect(diff).toEqual([
      { key: 'smtp.password', label: '비밀번호', group: '이메일(SMTP)', before: '', after: '값 변경됨', secret: true },
    ]);
  });

  it('지우기 표시된 비밀 키는 빈 문자열을 명시적으로 보낸다', () => {
    const { payload, diff } = buildSettingsPayload(
      { ...ORIGINAL, 'smtp.password': '' },
      ORIGINAL,
      new Set(['smtp.password']),
    );
    expect(payload).toEqual({ 'smtp.password': '' });
    expect(diff[0]).toMatchObject({ key: 'smtp.password', after: '값 삭제됨', secret: true });
  });

  it('서버가 준 마스크 값이 폼에 들어와 있어도 그 키를 되돌려보내지 않는다', () => {
    // 진짜 위험은 "빌더가 마스크를 만든다"가 아니라(그럴 코드 경로가 없다)
    // "서버가 준 마스크가 폼 값으로 들어와 그대로 되돌아간다" 쪽이다. 그러면 서버
    // isMaskSentinel(`****` 접두 + 길이 4 또는 8)이 그 키를 드롭해 "204 성공 + 아무 일도 없음"이
    // 되거나, 길이가 어긋나면 마스크 문자열 자체가 진짜 비밀번호로 저장된다.
    // 다른 비밀 키(ai.cli_oauth_token)에 진짜 새 값을 함께 넣어 payload 가 애초에 비어 있어서
    // 통과하는 게 아님을 보장한다 — 같은 비밀 분기 코드를 지나는 양성 대조군.
    const { payload, diff } = buildSettingsPayload(
      { ...ORIGINAL, 'smtp.password': '****ab12', 'ai.cli_oauth_token': 'n3wT0ken' },
      ORIGINAL,
      new Set(),
    );
    expect(payload).toEqual({ 'ai.cli_oauth_token': 'n3wT0ken' });
    expect(payload).not.toHaveProperty('smtp.password');
    expect(Object.values(payload).some((v) => v.startsWith('****'))).toBe(false);
    expect(diff.some((d) => d.key === 'smtp.password')).toBe(false);
  });

  it('STARTTLS 는 리터럴 문자열 true/false 로 나간다', () => {
    const off = buildSettingsPayload({ ...ORIGINAL, 'smtp.starttls': 'false' }, ORIGINAL, new Set());
    expect(off.payload['smtp.starttls']).toBe('false');
    // JSON boolean 이나 "on" 을 보내면 저장은 200 으로 성공하고 다운스트림에서 STARTTLS 가
    // 조용히 꺼진다(화면은 켜짐으로 보인다). 서버 validateValues 에 이 키의 case 가 하나도 없어
    // 클라이언트가 유일한 방어선이다.
    expect(typeof off.payload['smtp.starttls']).toBe('string');

    const on = buildSettingsPayload(
      { ...ORIGINAL, 'smtp.starttls': 'false' },
      { ...ORIGINAL, 'smtp.starttls': 'false' },
      new Set(),
    );
    expect(on.payload).toEqual({});
  });

  it('ai.api_key 와 embedding.api_key 는 라벨이 둘 다 "API 키" 지만 group 으로 구별된다(리뷰 L1)', () => {
    // 두 키를 동시에 바꿔야 라벨 충돌이 실제로 드러난다 — 하나만 바꾸면 diff 가 한 줄이라
    // 구별할 필요 자체가 생기지 않는다.
    const { diff } = buildSettingsPayload(
      { ...ORIGINAL, 'ai.api_key': 'n3wAnthropicKey', 'embedding.api_key': 'n3wEmbeddingKey' },
      ORIGINAL,
      new Set(),
    );
    const apiKeyRows = diff.filter((d) => d.label === 'API 키');
    expect(apiKeyRows).toHaveLength(2);
    // label 은 둘 다 같지만 group 은 서로 달라야 다이얼로그에서 구별된다.
    expect(new Set(apiKeyRows.map((d) => d.group)).size).toBe(2);
    expect(apiKeyRows.map((d) => d.group).sort()).toEqual(['AI 에이전트', '임베딩']);
  });
});
