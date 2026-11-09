/**
 * AI 도구 실행 뒤 무효화 키 회귀 테스트 (WD-667 #5)
 *
 * 채팅에서 execute_proactive_job 을 실행하면 예전엔 ['proactive','jobs'] 만 무효화해, 실행 이력 탭의 목록
 * (['proactive','executions',jobId,'list',…])이 최대 30초(staleTime) 낡은 채 남고 실행 중 폴링도 시작되지 않았다.
 * 이제 도구 입력의 작업 id 로 그 작업의 실행 목록을 함께 무효화한다.
 */
import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it } from 'vitest';

import { getInvalidationKeys } from './invalidationMap';

/** 도구가 돌려준 키들로 실제 캐시를 무효화해, 어떤 캐시가 낡음 표시를 받았는지 본다 */
async function invalidatedAfter(toolName: string, input: Record<string, unknown> | undefined, seed: unknown[][]) {
  const client = new QueryClient();
  for (const key of seed) client.setQueryData(key, {});
  for (const key of getInvalidationKeys(toolName, input)) {
    await client.invalidateQueries({ queryKey: key });
  }
  return (key: unknown[]) => client.getQueryState(key)?.isInvalidated;
}

describe('execute_proactive_job 무효화 (WD-667)', () => {
  const LIST = ['proactive', 'executions', 3, 'list', { limit: 20, offset: 0 }];
  const SINGLE = ['proactive', 'executions', 3, 9];
  const OTHER_JOB_LIST = ['proactive', 'executions', 4, 'list', { limit: 20, offset: 0 }];
  const JOBS = ['proactive', 'jobs'];

  it('그 작업의 실행 목록과 작업 목록을 무효화하고, 단건 실행·다른 작업 목록은 두지 않는다', async () => {
    const isInvalidated = await invalidatedAfter('mcp__firehub__execute_proactive_job', { id: 3 }, [
      LIST,
      SINGLE,
      OTHER_JOB_LIST,
      JOBS,
    ]);
    expect(isInvalidated(LIST)).toBe(true);
    expect(isInvalidated(JOBS)).toBe(true);
    expect(isInvalidated(SINGLE)).toBe(false);
    expect(isInvalidated(OTHER_JOB_LIST)).toBe(false);
  });

  it('작업 id 가 문자열("3")이어도 그 작업의 실행 목록만 무효화한다 (WD-671)', async () => {
    const isInvalidated = await invalidatedAfter('mcp__firehub__execute_proactive_job', { id: '3' }, [
      LIST,
      SINGLE,
      OTHER_JOB_LIST,
      JOBS,
    ]);
    expect(isInvalidated(LIST)).toBe(true);
    expect(isInvalidated(JOBS)).toBe(true);
    expect(isInvalidated(SINGLE)).toBe(false);
    expect(isInvalidated(OTHER_JOB_LIST)).toBe(false);
  });

  it('작업 id 가 형식이 잘못되면("abc"·1e21) 모든 실행 캐시로 넓혀 낡은 목록을 남기지 않는다 (WD-671)', async () => {
    for (const id of ['abc', 1e21]) {
      const isInvalidated = await invalidatedAfter('mcp__firehub__execute_proactive_job', { id }, [LIST, OTHER_JOB_LIST]);
      expect(isInvalidated(LIST)).toBe(true);
      expect(isInvalidated(OTHER_JOB_LIST)).toBe(true);
    }
  });

  it('입력에 작업 id 가 없으면 모든 실행 캐시를 무효화해 낡은 목록을 남기지 않는다', async () => {
    const isInvalidated = await invalidatedAfter('mcp__firehub__execute_proactive_job', undefined, [LIST, JOBS]);
    expect(isInvalidated(LIST)).toBe(true);
    expect(isInvalidated(JOBS)).toBe(true);
  });

  it('입력이 없는 다른 도구는 예전 키 그대로다(대조군)', () => {
    expect(getInvalidationKeys('mcp__firehub__create_dataset')).toEqual([['datasets']]);
  });
});
