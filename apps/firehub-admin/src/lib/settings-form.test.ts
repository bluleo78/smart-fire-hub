import { describe, expect, it } from 'vitest';

import { validateForm } from './settings-form';

/**
 * `ALL_SETTING_KEYS` 15개(타입형 AI 설정 전환, Task 13 — `ai.api_key`/`ai.agent_type`/
 * `ai.cli_oauth_token` 3키는 `ai.credential` 문서로 옮겨가 이 카탈로그·이 폼에 없다. Ruling #48,
 * fix round 1 — `ai.model` 도 `AiCredentialSection` 으로 옮겨가 하나 더 빠졌다) 전부를 유효한
 * 값으로 채운다. 일부만 채우면 나머지 키는 `original[key]` 가 `undefined` 라 "값이 원본과
 * 같다" 판정이 항상 거짓이 되어 그 키까지 검증 대상이 되고, 빈 값을 거부하는 키
 * (`smtp.port`/`ai.system_prompt` 등)가 무더기로 걸려 이 테스트의 의도(특정 키만 바꿨을 때의
 * 동작)를 가린다.
 *
 * `ai.model` 은 이제 `ALL_SETTING_KEYS` 밖이라 여기 남겨 둬도 `validateForm` 이 아예 보지
 * 않는다 — 그래도 지우지 않는다: 이 상수가 "폼에 실제로 있는 값"의 스냅샷 역할도 겸하므로,
 * 지우면 이 파일만 보고 오해하기 쉽다(실제로는 다른 화면 조각이 그 키를 다룬다).
 */
const ORIGINAL = {
  'ai.model': 'claude-sonnet-5',
  'ai.max_turns': '20',
  'ai.system_prompt': '당신은 소방 데이터 분석가입니다.',
  'ai.temperature': '0.7',
  'ai.max_tokens': '8192',
  'ai.session_max_tokens': '50000',
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

  it('빈 비밀 키는 검증하지 않는다 — "유지" 의미다', () => {
    // 타입형 AI 설정 전환(Task 13) 이후 이 카탈로그에 남은 비밀 키(embedding.api_key/
    // smtp.password) 는 둘 다 validate 가 없다 — 빈값 거부 규칙을 가진 유일한 비밀
    // (ai.api_key)이 ai.credential 문서로 옮겨갔기 때문이다. 그래서 "빈 비밀은 검증 자체를
    // 건너뛴다"(settings-form.ts:20 `spec.secret && value === ''`)는 가드가 지금 이
    // 카탈로그에서는 공백/빈 문자열 구별을 관찰할 수 있는 secret+validate 조합이 없어
    // 공백(' ') 케이스까지는 증명하지 못한다 — 알려진 커버리지 공백이다(Task 13 리포트 참고).
    const found = validateForm({ ...ORIGINAL, 'embedding.api_key': '' }, ORIGINAL);
    expect(found).toEqual({});
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
