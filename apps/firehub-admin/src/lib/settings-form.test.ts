import { describe, expect, it } from 'vitest';

import { validateForm } from './settings-form';

/**
 * `ALL_SETTING_KEYS` 10개 전부를 유효한 값으로 채운다. 일부만 채우면 나머지 키는
 * `original[key]` 가 `undefined` 라 "값이 원본과 같다" 판정이 항상 거짓이 되어 그 키까지 검증
 * 대상이 되고, 빈 값을 거부하는 키(`smtp.port`/`embedding.model` 등)가 무더기로 걸려 이
 * 테스트의 의도(특정 키만 바꿨을 때의 동작)를 가린다.
 */
const ORIGINAL = {
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
    // smtp.port 를 무효한 값으로 바꾸고 동시에 검사하면서, 값이 그대로인 embedding.model 은
    // 걸리지 않는다는 것을 같은 호출에서 함께 확인한다(양성 대조군 겸 "건너뛴다" 증명).
    const found = validateForm({ ...ORIGINAL, 'smtp.port': '99999' }, ORIGINAL);
    expect(found).toEqual({ 'smtp.port': '1~65535 사이의 정수를 입력하세요' });
    expect(found).not.toHaveProperty('embedding.model');
  });

  it('빈 비밀 키는 검증하지 않는다 — "유지" 의미다', () => {
    // 이 카탈로그의 비밀 키(embedding.api_key/smtp.password)는 둘 다 validate 가 없어,
    // "빈 비밀은 검증 자체를 건너뛴다"(settings-form.ts `spec.secret && value === ''`)는 가드의
    // 공백(' ') 케이스까지는 관찰할 수 없다 — 알려진 커버리지 공백이다.
    const found = validateForm({ ...ORIGINAL, 'embedding.api_key': '' }, ORIGINAL);
    expect(found).toEqual({});
  });

  it('여러 키가 동시에 무효하면 전부 담긴다', () => {
    const found = validateForm(
      { ...ORIGINAL, 'smtp.port': '99999', 'embedding.model': '' },
      ORIGINAL,
    );
    expect(found).toEqual({
      'smtp.port': '1~65535 사이의 정수를 입력하세요',
      'embedding.model': '임베딩 모델은 비어있을 수 없습니다',
    });
  });
});
