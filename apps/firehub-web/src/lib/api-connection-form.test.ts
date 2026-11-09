import { describe, expect, it } from 'vitest';

import {
  AUTH_TYPE_LABEL,
  AUTH_TYPE_OPTIONS,
  authTypeBadgeVariant,
  authTypeLabel,
  basicUsernameError,
  isApiConnectionAuthType,
} from './api-connection-form';

/** API 연결 칸 검증 도우미 — Basic 인증 아이디 규칙·인증 방식 표시 이름(WD-681). */
describe('api-connection-form', () => {
  it('Basic 아이디가 비면(서버 isBlank 기준) 빈 값 안내, 콜론이 있으면 거절한다', () => {
    expect(basicUsernameError('')).toBe('아이디를 입력하세요.');
    expect(basicUsernameError('  ')).toBe('아이디를 입력하세요.');
    expect(basicUsernameError('a:b')).toBe('아이디에 콜론(:)을 넣을 수 없습니다.');
    expect(basicUsernameError('svc-user')).toBeUndefined();
    // NBSP 는 서버처럼 공백이 아니라 값으로 본다
    expect(basicUsernameError(' ')).toBeUndefined();
  });

  it('인증 방식 선택지는 API Key·Bearer·Basic 이고, 모두 표시 이름이 있다', () => {
    expect(AUTH_TYPE_OPTIONS).toEqual(['API_KEY', 'BEARER', 'BASIC']);
    expect(AUTH_TYPE_LABEL.BASIC).toEqual({ badge: 'Basic', full: 'Basic Auth' });
    for (const type of AUTH_TYPE_OPTIONS) expect(AUTH_TYPE_LABEL[type].badge).toBeTruthy();
  });

  it('조회한 인증 방식 표시 이름 — 받는 방식은 공용 표 이름, 옛 OAUTH2 는 서버 값 그대로 보인다', () => {
    expect(authTypeLabel('BEARER', 'badge')).toBe('Bearer');
    expect(authTypeLabel('BEARER', 'full')).toBe('Bearer Token');
    expect(authTypeLabel('BASIC', 'full')).toBe('Basic Auth');
    expect(authTypeLabel('OAUTH2', 'badge')).toBe('OAUTH2');
    expect(authTypeBadgeVariant('OAUTH2')).toBe('secondary');
    expect(isApiConnectionAuthType('API_KEY')).toBe(true);
    expect(isApiConnectionAuthType('OAUTH2')).toBe(false);
    // 객체 프로토타입 키(toString 등)를 받는 방식으로 오인하지 않는다
    expect(isApiConnectionAuthType('toString')).toBe(false);
  });
});
