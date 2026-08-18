import { describe, it, expect } from 'vitest';
import { homedir, tmpdir } from 'os';
import { join } from 'path';
import {
  attachmentsDir,
  legacyAttachmentsDir,
  legacyTranscriptDir,
  opencodeWorkspaceDir,
  tenantSegment,
  proactiveReportDir,
  sdkChatFilesDir,
  transcriptDir,
  workspaceDir,
} from './tenant-paths.js';

describe('tenant-paths', () => {
  // TP-01: 모든 종류가 테넌트 세그먼트를 갖는다. 하나라도 빠지면 그 종류만 조용히 전역 경로에 남는다.
  it('TP-01: every artifact kind carries the tenant segment', () => {
    const root = join(homedir(), '.firehub');
    expect(workspaceDir(7, 42)).toBe(join(root, 'workspaces', 't7', '42'));
    expect(opencodeWorkspaceDir(7, 42)).toBe(join(root, 'workspaces-opencode', 't7', '42'));
    expect(transcriptDir(7)).toBe(join(root, 'transcripts', 't7'));
    expect(attachmentsDir(7)).toBe(join(root, 'session-attachments', 't7'));
  });

  // TP-02: 테넌트 1 도 예외가 없다. API 쪽 물리 스키마는 테넌트 1 을 `data` 로 남겼지만(신규만
  // `data_t{id}`), 파일 경로에 같은 예외를 복제하지 않기로 한 결정을 여기에 고정한다.
  it('TP-02: tenant 1 is not special-cased', () => {
    expect(tenantSegment(1)).toBe('t1');
    expect(transcriptDir(1)).toBe(join(homedir(), '.firehub', 'transcripts', 't1'));
    expect(transcriptDir(1)).not.toBe(legacyTranscriptDir());
  });

  // TP-03: 테넌트가 없으면 던진다. 전역 경로로 폴백하면 모든 테넌트가 한 디렉터리를 공유하는
  // 상태로 조용히 되돌아가는데, 그게 이 모듈이 막으려는 것 자체다.
  it.each([
    ['undefined', undefined],
    ['null', null],
    ['0', 0],
    ['음수', -1],
    ['소수', 1.5],
    ['숫자 문자열', '1'],
    ['NaN', Number.NaN],
  ])('TP-03: fail-closed on invalid tenant (%s)', (_label, value) => {
    expect(() => tenantSegment(value)).toThrow(/전역 경로 폴백 금지/);
  });

  // TP-05: SDK 경로의 첨부 다운로드 디렉터리도 테넌트를 담는다 — CLI 는 workspaceDir 아래라
  // 이미 담기므로, 한쪽만 빠지면 같은 논리적 산출물에 비대칭이 남는다.
  it('TP-05: the SDK chat-files dir carries the tenant segment too', () => {
    expect(sdkChatFilesDir(7, 42, 123)).toBe(
      join(tmpdir(), 'firehub-chat-files', 't7', '42-123'),
    );
    expect(() => sdkChatFilesDir(0, 42, 123)).toThrow(/전역 경로 폴백 금지/);
  });

  // TP-06: 프로액티브 리포트 디렉터리도 테넌트를 담는다 — 같은 tmpdir 안에서 한 종류만
  // 세그먼트를 갖고 있으면 도구를 가진 에이전트가 나머지를 통째로 열거할 수 있다.
  it('TP-06: the proactive report dir carries the tenant segment too', () => {
    expect(proactiveReportDir(7, 42, 123)).toBe(
      join(tmpdir(), 'proactive-report', 't7', '42-123'),
    );
    expect(() => proactiveReportDir(-1, 42, 123)).toThrow(/전역 경로 폴백 금지/);
  });

  // TP-04: 레거시 경로는 테넌트 디렉터리의 부모여야 한다 — 지연 이관 폴백과 TTL 순회가 둘 다
  // "베이스 아래 t* 하위" 라는 구조를 전제로 동작한다.
  it('TP-04: legacy dirs are the parents of the tenant dirs', () => {
    expect(transcriptDir(3).startsWith(legacyTranscriptDir())).toBe(true);
    expect(attachmentsDir(3).startsWith(legacyAttachmentsDir())).toBe(true);
  });
});
