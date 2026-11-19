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

/** 자동 양식 이름이 남은 양식 이름을 건너뛰고, 소진되면 할 일을 안내한다(WD-837). */
describe('createSmartJobWithTemplate 자동 양식 이름 건너뛰기·소진 안내 (WD-837)', () => {
  let client: FireHubApiClient;
  const dupBody = { status: 409, error: 'Conflict', code: 'DUPLICATE_NAME', message: '이미 있는 이름' };
  const args = {
    name: 'n',
    prompt: 'p',
    templateName: 'X 양식',
    templateStructure: { sections: [{ key: 'k', label: 'l' }], output_format: 'markdown' },
  };
  /** 「X 양식」, 「X 양식 (2)」 … 「X 양식 (count)」 */
  const existingNames = (count: number) =>
    Array.from({ length: count }, (_, i) => ({ name: i === 0 ? 'X 양식' : `X 양식 (${i + 1})` }));

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('첫 409 뒤 목록을 한 번 읽어 남은 이름을 건너뛰고 비어 있는 번호로 만든다', async () => {
    const names: string[] = [];
    nock(BASE_URL)
      .post('/proactive/templates', (b: Record<string, unknown>) => {
        names.push(b.name as string);
        return true;
      })
      .times(2)
      .reply(function () {
        return names.length === 1 ? [409, dupBody] : [201, { id: 70 }];
      });
    const listScope = nock(BASE_URL)
      .get('/proactive/templates')
      .query({ page: '0', size: '200' })
      .reply(200, existingNames(20));
    nock(BASE_URL).post('/proactive/jobs').reply(201, { id: 9 });

    await client.createSmartJobWithTemplate(args);

    expect(names).toEqual(['X 양식', 'X 양식 (21)']);
    expect(listScope.isDone()).toBe(true);
  });

  it('목록 읽기가 실패하면 순차 재시도하다 20번째에 소진 안내를 던진다', async () => {
    let calls = 0;
    nock(BASE_URL)
      .post('/proactive/templates', () => {
        calls += 1;
        return true;
      })
      .times(25)
      .reply(409, dupBody);
    nock(BASE_URL).get('/proactive/templates').query(true).reply(500, { message: 'boom' });

    await expect(client.createSmartJobWithTemplate(args)).rejects.toThrow(
      '자동 양식 이름을 정하지 못했습니다. 쓰지 않는 「X 양식」 양식을 지우거나 작업 이름을 바꾼 뒤 다시 만들어 주세요.',
    );
    expect(calls).toBe(20);
  });

  it('겹치지 않으면 목록을 읽지 않는다', async () => {
    nock(BASE_URL).post('/proactive/templates').reply(201, { id: 3 });
    nock(BASE_URL).post('/proactive/jobs').reply(201, { id: 4 });
    const listScope = nock(BASE_URL).get('/proactive/templates').query(true).reply(200, []);

    await client.createSmartJobWithTemplate(args);

    expect(listScope.isDone()).toBe(false);
  });
});
