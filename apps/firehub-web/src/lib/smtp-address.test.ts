import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import { isSenderAddressSyntax, isSmtpHostSyntax } from './smtp-address';

/**
 * 서버(SettingsService 의 SMTP 문법)와 **같은 판정**이어야 한다(#728).
 *
 * 값 목록을 이 파일에 따로 두지 않는다 — 서버 TC(SmtpAddressGrammarTest)와 **같은 픽스처 파일**을
 * 읽는다. 목록을 앱마다 따로 들고 있던 동안 두 번 어긋났다(서버만 파서를 더 돌림, `\s` 의 뜻이
 * 언어마다 다름). 이제 한쪽 문법만 고치면 그 앱의 테스트가 깨진다.
 *
 * `charSweep` 의 `accepted` 를 다시 만들 때: `SMTP_VECTORS_UPDATE=1 pnpm vitest run
 * src/lib/smtp-address.test.ts` — 그런 뒤 서버 TC 가 같은 값을 내는지 반드시 돌려 본다.
 */
const FIXTURE_PATH = resolve(
  __dirname,
  '../../../firehub-api/src/test/resources/fixtures/smtp-validation-vectors.json',
);

type Field = 'fromAddress' | 'host';
interface Sweep {
  field: Field;
  name: string;
  template: string;
  accepted: string;
}
interface Fixture {
  fromAddress: { accept: string[]; reject: string[] };
  host: { accept: string[]; reject: string[] };
  charSweep: Sweep[];
}

const fixture = JSON.parse(readFileSync(FIXTURE_PATH, 'utf8')) as Fixture;
const JUDGE: Record<Field, (raw: string) => boolean> = {
  fromAddress: isSenderAddressSyntax,
  host: isSmtpHostSyntax,
};

/** 사람이 읽을 수 있게 — 보이지 않는 문자가 든 값은 JSON 표기로 보여 준다. */
const show = (value: string) => JSON.stringify(value);

/**
 * template 의 `{c}` 자리에 U+0000~U+FFFF 를 하나씩 넣어, 통과하는 코드 단위를 16진 범위 목록으로
 * 만든다(예: `21,23-27`). 서버 TC 의 같은 이름 함수와 같은 표기다.
 */
function sweep(field: Field, template: string): string {
  const ranges: string[] = [];
  let start = -1;
  for (let c = 0; c <= 0x10000; c++) {
    const ok = c < 0x10000 && JUDGE[field](template.replace('{c}', () => String.fromCharCode(c)));
    if (ok && start < 0) start = c;
    if (!ok && start >= 0) {
      ranges.push(start === c - 1 ? start.toString(16) : `${start.toString(16)}-${(c - 1).toString(16)}`);
      start = -1;
    }
  }
  return ranges.join(',');
}

describe('smtp-address — 서버와 함께 읽는 픽스처', () => {
  it('픽스처가 비어 있지 않다(읽기 실패로 공허하게 통과하지 않는다)', () => {
    expect(fixture.fromAddress.accept.length).toBeGreaterThan(20);
    expect(fixture.fromAddress.reject.length).toBeGreaterThan(60);
    expect(fixture.host.accept.length).toBeGreaterThan(5);
    expect(fixture.host.reject.length).toBeGreaterThan(15);
    expect(fixture.charSweep.length).toBeGreaterThan(10);
  });

  it.each(fixture.fromAddress.accept.map((v) => [show(v), v]))('발신자 주소 %s 는 받는다', (_, value) => {
    expect(isSenderAddressSyntax(value)).toBe(true);
  });

  it.each(fixture.fromAddress.reject.map((v) => [show(v), v]))('발신자 주소 %s 는 받지 않는다', (_, value) => {
    expect(isSenderAddressSyntax(value)).toBe(false);
  });

  it.each(fixture.host.accept.map((v) => [show(v), v]))('호스트 %s 는 받는다', (_, value) => {
    expect(isSmtpHostSyntax(value)).toBe(true);
  });

  it.each(fixture.host.reject.map((v) => [show(v), v]))('호스트 %s 는 받지 않는다', (_, value) => {
    expect(isSmtpHostSyntax(value)).toBe(false);
  });

  // 문자 단위 일치 — 자리마다 BMP 전 코드 단위를 넣은 판정이 픽스처(=서버 TC 가 확인하는 값)와 같다.
  it.each(fixture.charSweep.map((s) => [s.name, s.template, s]))(
    '%s(%s) 자리에 통과하는 문자가 서버와 같다',
    (_, __, s) => {
      expect(sweep(s.field, s.template)).toBe(s.accepted);
    },
  );
});

// 픽스처 재생성 도구 — 평소에는 돌지 않는다. 문자열 치환으로 accepted 만 바꿔 나머지 표기를 보존한다.
if (process.env.SMTP_VECTORS_UPDATE) {
  let text = readFileSync(FIXTURE_PATH, 'utf8');
  for (const s of fixture.charSweep) {
    const anchor = `"template": ${JSON.stringify(s.template)},\n      "accepted": `;
    const at = text.indexOf(anchor);
    if (at < 0) throw new Error(`픽스처에서 ${s.template} 를 찾지 못했다`);
    const from = at + anchor.length;
    const to = text.indexOf('\n', from);
    text = text.slice(0, from) + JSON.stringify(sweep(s.field, s.template)) + text.slice(to);
  }
  writeFileSync(FIXTURE_PATH, text);
}
