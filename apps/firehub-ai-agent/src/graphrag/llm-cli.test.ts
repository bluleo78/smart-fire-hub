import { describe, it, expect, vi, beforeEach } from 'vitest';
import { EventEmitter } from 'node:events';

// node:child_process.spawn을 목킹해 실제 claude CLI를 실행하지 않는다.
const spawnMock = vi.fn();
vi.mock('node:child_process', () => ({
  spawn: (...args: unknown[]) => spawnMock(...args),
}));

// 실제 child_process 자식 프로세스를 흉내내는 가짜 객체를 만든다.
// stdin.write/end는 호출 기록만 남기고, stdout/stderr/close는 EventEmitter로 이벤트를 발생시킬 수 있게 한다.
function createFakeChild() {
  const child = new EventEmitter() as EventEmitter & {
    stdin: { write: ReturnType<typeof vi.fn>; end: ReturnType<typeof vi.fn> };
    stdout: EventEmitter;
    stderr: EventEmitter;
  };
  child.stdin = { write: vi.fn(), end: vi.fn() };
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();
  return child;
}

describe('createCliCompleter', () => {
  beforeEach(() => {
    spawnMock.mockReset();
  });

  it('올바른 argv로 claude CLI를 스폰한다(-p, --append-system-prompt, --model)', async () => {
    const child = createFakeChild();
    spawnMock.mockReturnValue(child);

    const { createCliCompleter } = await import('./llm-cli.js');
    const complete = createCliCompleter({ model: 'claude-haiku-4-5' });
    const promise = complete('시스템 프롬프트', '사용자 텍스트');

    expect(spawnMock).toHaveBeenCalledTimes(1);
    const [command, args] = spawnMock.mock.calls[0] as [string, string[], unknown];
    expect(command).toBe('claude');
    expect(args).toEqual(['-p', '--append-system-prompt', '시스템 프롬프트', '--model', 'claude-haiku-4-5']);
    expect(child.stdin.write).toHaveBeenCalledWith('사용자 텍스트');
    expect(child.stdin.end).toHaveBeenCalled();

    child.stdout.emit('data', Buffer.from('결과'));
    child.emit('close', 0);
    await expect(promise).resolves.toBe('결과');
  });

  it('model이 없으면 --model을 붙이지 않는다', async () => {
    const child = createFakeChild();
    spawnMock.mockReturnValue(child);

    const { createCliCompleter } = await import('./llm-cli.js');
    const complete = createCliCompleter();
    const promise = complete('sys', 'user');

    const [, args] = spawnMock.mock.calls[0] as [string, string[], unknown];
    expect(args).toEqual(['-p', '--append-system-prompt', 'sys']);

    child.stdout.emit('data', Buffer.from('ok'));
    child.emit('close', 0);
    await expect(promise).resolves.toBe('ok');
  });

  it('stdout data를 이어붙여 close(0)에서 resolve한다', async () => {
    const child = createFakeChild();
    spawnMock.mockReturnValue(child);

    const { createCliCompleter } = await import('./llm-cli.js');
    const complete = createCliCompleter();
    const promise = complete('sys', 'user');

    child.stdout.emit('data', Buffer.from('part1 '));
    child.stdout.emit('data', Buffer.from('part2'));
    child.emit('close', 0);

    await expect(promise).resolves.toBe('part1 part2');
  });

  it('non-zero exit code면 stderr를 포함한 에러로 reject한다', async () => {
    const child = createFakeChild();
    spawnMock.mockReturnValue(child);

    const { createCliCompleter } = await import('./llm-cli.js');
    const complete = createCliCompleter();
    const promise = complete('sys', 'user');

    child.stderr.emit('data', Buffer.from('인증 실패'));
    child.emit('close', 1);

    await expect(promise).rejects.toThrow(/인증 실패/);
  });
});
