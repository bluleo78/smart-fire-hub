import { describe, expect, it } from 'vitest';

import { validateForm } from './settings-form';

/**
 * `ALL_SETTING_KEYS` 19개 전부를 유효한 값으로 채운다. 일부만 채우면 나머지 키는
 * `original[key]` 가 `undefined` 라 "값이 원본과 같다" 판정이 항상 거짓이 되어 그 키까지
 * 검증 대상이 되고, 빈 값을 거부하는 키(`smtp.port`/`ai.system_prompt` 등)가 무더기로
 * 걸려 이 테스트의 의도(특정 키만 바꿨을 때의 동작)를 가린다.
 */
const ORIGINAL = {
  'ai.model': 'claude-sonnet-5',
  'ai.max_turns': '20',
  'ai.system_prompt': '당신은 소방 데이터 분석가입니다.',
  'ai.temperature': '0.7',
  'ai.max_tokens': '8192',
  'ai.session_max_tokens': '50000',
  'ai.api_key': '',
  'ai.agent_type': 'cli',
  'ai.cli_oauth_token': '',
  'smtp.host': 'smtp.example.com',
  'smtp.port': '587',
  'smtp.username': 'mailer',
  'smtp.password': '',
  'smtp.starttls': 'true',
  'smtp.from_address': 'no-reply@example.com',
  'embedding.provider': 'OLLAMA',
  'embedding.model': 'bge-m3',
  'embedding.base_url': 'http://localhost:11434',
  'embedding.api_key': '',
};

describe('validateForm', () => {
  it('폼이 원본과 똑같으면 검증할 것이 없다', () => {
    expect(validateForm({ ...ORIGINAL }, ORIGINAL)).toEqual({});
  });

  it('값이 원본과 같은 키는 그 값이 원래 무효해도 검증하지 않는다 — 다른 변경 키만 걸린다', () => {
    // ai.max_turns 를 무효한 값으로 바꾸고 동시에 검사하면서, 값이 그대로인 ai.temperature 는
    // 걸리지 않는다는 것을 같은 호출에서 함께 확인한다(양성 대조군 겸 "건너뛴다" 증명).
    const found = validateForm({ ...ORIGINAL, 'ai.max_turns': '999' }, ORIGINAL);
    expect(found).toEqual({ 'ai.max_turns': '1~50 사이의 정수를 입력하세요' });
    expect(found).not.toHaveProperty('ai.temperature');
  });

  it('빈 비밀 키는 검증하지 않는다 — "유지" 의미라 ai.api_key 의 빈값 거부 규칙이 적용되면 안 된다', () => {
    const found = validateForm({ ...ORIGINAL, 'ai.api_key': '' }, ORIGINAL);
    expect(found).toEqual({});
  });

  it('공백만 있는 비밀 키는 빈 문자열이 아니므로 검증한다(ai.api_key 는 공백도 거부)', () => {
    // '' 와 '   ' 를 같은 것으로 취급하면 안 된다 — value === '' 엄격 비교라 공백은 검사를
    // 통과해 그대로 페이로드에 실릴 수 있다.
    const found = validateForm({ ...ORIGINAL, 'ai.api_key': '   ' }, ORIGINAL);
    expect(found).toEqual({ 'ai.api_key': 'API 키는 비워 둘 수 없습니다' });
  });

  it('여러 키가 동시에 무효하면 전부 담긴다', () => {
    const found = validateForm(
      { ...ORIGINAL, 'ai.max_turns': '999', 'ai.temperature': '9.9' },
      ORIGINAL,
    );
    expect(found).toEqual({
      'ai.max_turns': '1~50 사이의 정수를 입력하세요',
      'ai.temperature': '0.0~1.0 사이의 값을 입력하세요',
    });
  });
});
