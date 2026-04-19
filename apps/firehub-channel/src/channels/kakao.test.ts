import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { sendKakaoMessage } from './kakao.js';

beforeEach(() => nock.cleanAll());
afterEach(() => { if (!nock.isDone()) throw new Error('nock pending'); });

describe('sendKakaoMessage', () => {
  it('전송 성공', async () => {
    nock('https://kapi.kakao.com')
      .post('/v2/api/talk/memo/default/send')
      .reply(200, { result_code: 0 });

    await expect(sendKakaoMessage({ accessToken: 'test-token', text: '안녕하세요' })).resolves.toBeUndefined();
  });

  it('토큰 만료 (401) → auth_error throw', async () => {
    nock('https://kapi.kakao.com')
      .post('/v2/api/talk/memo/default/send')
      .reply(401, { msg: 'not authorized' });

    await expect(sendKakaoMessage({ accessToken: 'expired', text: '메시지' })).rejects.toThrow('auth_error');
  });

  it('서버 오류 (500) → upstream_error throw', async () => {
    nock('https://kapi.kakao.com')
      .post('/v2/api/talk/memo/default/send')
      .reply(500);

    await expect(sendKakaoMessage({ accessToken: 'token', text: '메시지' })).rejects.toThrow('upstream_error');
  });
});
