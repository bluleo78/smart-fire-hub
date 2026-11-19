import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import nock from 'nock';

import { FireHubApiClient } from '../api-client.js';
import { numberedTemplateName } from './proactive-api.js';

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 42;

/** 자동 양식 이름 번호 후보(WD-824) — 웹 numberedGoalTemplateName 과 같은 모양이어야 한다. */
describe('numberedTemplateName', () => {
  it('1번째는 그대로, 2번째부터 번호를 붙인다', () => {
    expect(numberedTemplateName('A 양식', 1)).toBe('A 양식');
    expect(numberedTemplateName('A 양식', 2)).toBe('A 양식 (2)');
  });

  it('긴 이름은 이름 쪽을 잘라 「양식」 꼬리를 지킨다', () => {
    const name = numberedTemplateName(`${'가'.repeat(199)} 양식`, 2);
    expect(name.length).toBeLessThanOrEqual(200);
    expect(name.endsWith(' 양식 (2)')).toBe(true);
  });

  // 서버 @Size(max=200)는 UTF-16 단위로 센다(WD-699). 이모지 1개는 2단위라 코드 포인트 기준으로 자르면 400 이 난다.
  it('100개 이모지 작업 이름도 UTF-16 200 안에 들고 마지막 이모지가 온전하다', () => {
    const name = numberedTemplateName(`${'😀'.repeat(100)} 양식`, 1);
    expect(name.length).toBeLessThanOrEqual(200);
    expect(name.endsWith(' 양식')).toBe(true);
    const lastBeforeTail = name.charCodeAt(name.length - ' 양식'.length - 1);
    expect(lastBeforeTail >= 0xdc00 && lastBeforeTail <= 0xdfff).toBe(true);
  });

  it('100개 이모지 작업 이름의 2회차도 UTF-16 200 안에 들고 번호 꼬리를 지킨다', () => {
    const name = numberedTemplateName(`${'😀'.repeat(100)} 양식`, 2);
    expect(name.length).toBeLessThanOrEqual(200);
    expect(name.endsWith(' 양식 (2)')).toBe(true);
  });

  it('자르는 자리가 이모지 쌍 가운데면 high surrogate 를 떼어 쌍을 가르지 않는다', () => {
    // 이름 쪽 방 193단위(200 - ' 양식 (2)' 7단위)에서 끝 단위가 high surrogate 이므로 한 단위 더 줄여 96개만 남긴다.
    const name = numberedTemplateName(`${'😀'.repeat(250)} 양식`, 2);
    expect(name.length).toBeLessThanOrEqual(200);
    expect(name).toBe(`${'😀'.repeat(96)} 양식 (2)`);
  });

  it('자르는 자리의 공백은 지운다', () => {
    expect(numberedTemplateName(`${'a'.repeat(190)}${' '.repeat(9)} 양식`, 2)).toBe(`${'a'.repeat(190)} 양식 (2)`);
  });
});

/** createSmartJobWithTemplate 공개 메서드 경로에서도 양식 이름 길이가 서버 한도 안인지 확인한다(WD-834). */
describe('createSmartJobWithTemplate 양식 이름 길이 (WD-834)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('100개 이모지 작업 이름으로 만든 양식 POST 본문 name 은 UTF-16 200 이하다', async () => {
    const sentNames: string[] = [];
    // 본문 name 을 캡처만 하고 통과시킨다 — 길이 검증은 테스트 본문에서 한다.
    nock(BASE_URL)
      .post('/proactive/templates', (b: Record<string, unknown>) => {
        sentNames.push(b.name as string);
        return true;
      })
      .reply(201, { id: 3 });
    nock(BASE_URL).post('/proactive/jobs').reply(201, { id: 4 });

    await client.createSmartJobWithTemplate({
      name: 'n',
      prompt: 'p',
      templateName: `${'😀'.repeat(100)} 양식`,
      templateStructure: { sections: [{ key: 'k', label: 'l' }], output_format: 'markdown' },
    });

    expect(sentNames).toHaveLength(1);
    expect(sentNames[0].length).toBeLessThanOrEqual(200);
    expect(sentNames[0].endsWith(' 양식')).toBe(true);
  });
});
