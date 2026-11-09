/**
 * html-to-pdf 렌더러 요청 판정 단위 테스트 (WD-617)
 *
 * 자기완결형 스킴(data:·blob:·about:)만 허용하고, 내부·외부를 가리지 않고 네트워크/파일 요청은
 * 모두 막는지 확인한다. 정상 리포트는 외부 리소스가 필요 없어 공인 https 도 막는 것이 정책이다.
 */
import { describe, it, expect } from 'vitest';
import { isPdfRequestAllowed } from './pdf-request-guard.js';

describe('isPdfRequestAllowed', () => {
  it.each([
    'data:image/png;base64,iVBORw0KGgo=',
    'data:text/css,body{color:red}',
    'blob:null/1b2c3d4e-0000-0000-0000-000000000000',
    'about:blank',
    'about:srcdoc',
  ])('자기완결형 콘텐츠는 허용한다: %s', (url) => {
    expect(isPdfRequestAllowed(url)).toBe(true);
  });

  it.each([
    // 루프백·사설·링크로컬(메타데이터)
    'http://127.0.0.1:5010/api/v1/admin',
    'http://localhost/',
    'http://169.254.169.254/latest/meta-data/',
    'http://10.0.0.5/',
    'http://192.168.1.1/',
    'http://172.16.0.1/',
    'http://0.0.0.0/',
    'http://2130706433/', // 127.0.0.1 의 10진 표기
    // IPv6 루프백·IPv4 매핑·ULA
    'http://[::1]/',
    'http://[::ffff:127.0.0.1]/',
    'http://[fd00::1]/',
    // 공인 호스트도 막는다 — 리포트는 외부 리소스가 필요 없다
    'https://example.com/logo.png',
    'https://fonts.googleapis.com/css2?family=Nanum+Gothic',
    // 네트워크 외 스킴
    'file:///etc/passwd',
    'ftp://example.com/x',
    'ws://127.0.0.1:5020/',
    'chrome://settings',
    'javascript:alert(1)',
  ])('그 밖의 요청은 막는다: %s', (url) => {
    expect(isPdfRequestAllowed(url)).toBe(false);
  });

  it('해석할 수 없는 URL 은 막는다(fail-closed)', () => {
    expect(isPdfRequestAllowed('')).toBe(false);
    expect(isPdfRequestAllowed('not a url')).toBe(false);
  });
});
