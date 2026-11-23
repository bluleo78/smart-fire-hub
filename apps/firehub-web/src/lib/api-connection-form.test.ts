import { describe, expect, it } from 'vitest';

import {
  AUTH_TYPE_LABEL,
  AUTH_TYPE_OPTIONS,
  authTypeBadgeVariant,
  authTypeLabel,
  baseUrlError,
  basicUsernameError,
  connectionNameError,
  isApiConnectionAuthType,
} from './api-connection-form';

/** 접두를 포함해 정확히 n자인 https URL */
const URL_PREFIX = 'https://x.com/';
const urlOfLength = (n: number) => URL_PREFIX + 'a'.repeat(n - URL_PREFIX.length);

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

  it('connectionNameError — 빈칸은 입력 문구, 앞뒤 공백을 뗀 100자까지 받고 101자는 길이 문구 (WD-1007)', () => {
    expect(connectionNameError('   ')).toBe('연결 이름을 입력하세요.');
    expect(connectionNameError('a'.repeat(100))).toBeUndefined();
    expect(connectionNameError('  ' + 'a'.repeat(100) + ' ')).toBeUndefined();
    expect(connectionNameError('a'.repeat(101))).toBe('연결 이름은 100자 이하로 입력하세요.');
  });

  it('baseUrlError — 정확히 500자는 받고 501자는 길이 문구, 빈칸 문구가 먼저다 (WD-1007)', () => {
    expect(baseUrlError(urlOfLength(500))).toBeUndefined();
    expect(baseUrlError(urlOfLength(501))).toBe('Base URL은 500자 이하로 입력하세요.');
  });

  it('baseUrlError — 빈 값은 입력 문구, 소문자 스킴만 받고, 형식은 공용 검사에 맡긴다', () => {
    expect(baseUrlError('')).toBe('Base URL을 입력하세요.');
    expect(baseUrlError('   ')).toBe('Base URL을 입력하세요.');
    expect(baseUrlError('ftp://x')).toBe('Base URL은 http 또는 https로 시작해야 합니다.');
    // 서버 DTO 는 대문자 스킴을 거절하므로 API 연결은 대문자를 막는다
    expect(baseUrlError('HTTPS://host')).toBe('Base URL은 http 또는 https로 시작해야 합니다.');
    expect(baseUrlError('https://')).toBe('Base URL 형식이 올바르지 않습니다.');
    expect(baseUrlError(' https://host ')).toBeUndefined();
    // 서버가 URI.create 로 저장해 공백을 어디서든 거절하므로 호스트 밖 공백도 막는다
    expect(baseUrlError('https://host/a b')).toBe('Base URL 형식이 올바르지 않습니다.');
    expect(baseUrlError('https://host?q=a b')).toBe('Base URL 형식이 올바르지 않습니다.');
    // 사용자 정보는 서버가 URI.create 전에 떼므로(BASIC) 그 안의 공백은 받는다
    expect(baseUrlError('https://user:p ss@host/v1')).toBeUndefined();
    expect(baseUrlError('https://user:p ss@host/a b')).toBe('Base URL 형식이 올바르지 않습니다.');
    // 경로의 @ 는 사용자 정보가 아니다
    expect(baseUrlError('https://host/a b@c')).toBe('Base URL 형식이 올바르지 않습니다.');
  });
});
