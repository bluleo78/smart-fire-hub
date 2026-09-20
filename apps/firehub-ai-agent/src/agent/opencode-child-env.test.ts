import { describe, expect, it } from 'vitest';
import {
  EXTRA_ENV_VAR,
  HARD_DENIED,
  buildOpenCodeChildEnv,
  parseExtraEnvNames,
} from './opencode-child-env.js';

/**
 * opencode 자식 env allowlist (이슈 #696).
 *
 * <p>이 스위트의 핵심은 "허용 목록이 맞는가"가 아니라 <b>차단이 어떤 경로로도 뚫리지 않는가</b>다
 * — denylist 시절의 사고는 전부 "이름 하나가 목록에 없었다"였고, allowlist 로 뒤집은 뒤의 사고는
 * "허용 규칙이 너무 넓었다"나 "탈출구로 새어 나왔다"의 모양을 갖는다.
 */
describe('buildOpenCodeChildEnv', () => {
  it('ENV-01: 허용 목록에 없는 이름은 전부 떨어진다 — 모르는 이름이 기본 차단이라는 뜻', () => {
    const { env } = buildOpenCodeChildEnv({
      PATH: '/usr/bin',
      SOME_FUTURE_PROVIDER_TOKEN: 'secret',
      RANDOM_THING: 'x',
    });

    expect(env.PATH).toBe('/usr/bin');
    expect(env.SOME_FUTURE_PROVIDER_TOKEN).toBeUndefined();
    expect(env.RANDOM_THING).toBeUndefined();
  });

  it('ENV-02: 실측상 필요한 키(PATH/HOME)와 운영 전용 키(프록시·CA·npm)는 통과한다', () => {
    const { env } = buildOpenCodeChildEnv({
      PATH: '/usr/bin',
      HOME: '/home/appuser',
      HTTPS_PROXY: 'http://proxy:3128',
      https_proxy: 'http://proxy:3128',
      NODE_EXTRA_CA_CERTS: '/etc/ssl/corp.pem',
      npm_config_registry: 'https://npm.corp/',
      LC_ALL: 'ko_KR.UTF-8',
    });

    expect(env).toMatchObject({
      PATH: '/usr/bin',
      HOME: '/home/appuser',
      HTTPS_PROXY: 'http://proxy:3128',
      https_proxy: 'http://proxy:3128',
      NODE_EXTRA_CA_CERTS: '/etc/ssl/corp.pem',
      npm_config_registry: 'https://npm.corp/',
      LC_ALL: 'ko_KR.UTF-8',
    });
  });

  it('ENV-03: 차단 목록의 이름은 하나도 통과하지 못한다 (denylist 시절 19개 전부)', () => {
    const parent: NodeJS.ProcessEnv = { PATH: '/usr/bin' };
    for (const name of HARD_DENIED) parent[name] = 'leaked';

    const { env } = buildOpenCodeChildEnv(parent);

    for (const name of HARD_DENIED) {
      expect(env[name], `${name} 이 자식 env 에 남았다`).toBeUndefined();
    }
  });

  it('ENV-04: 허용 규칙과 차단 목록의 교집합이 비어 있다 — 둘이 겹치면 순서에 따라 결과가 달라진다', () => {
    const parent: NodeJS.ProcessEnv = {};
    for (const name of HARD_DENIED) parent[name] = 'x';

    const { env } = buildOpenCodeChildEnv(parent);

    expect(Object.keys(env)).toEqual([]);
  });

  it('ENV-05: NODE_OPTIONS 는 허용하지 않는다 — --require 로 자식에 코드를 주입할 수 있다', () => {
    const { env } = buildOpenCodeChildEnv({
      PATH: '/usr/bin',
      NODE_OPTIONS: '--require /tmp/evil.js',
    });

    expect(env.NODE_OPTIONS).toBeUndefined();
  });

  it('ENV-06: XDG_CONFIG_HOME 은 부모에서 상속하지 않는다 — 호출부가 프로세스 전용 디렉터리로 덮어쓴다', () => {
    const { env } = buildOpenCodeChildEnv({
      PATH: '/usr/bin',
      XDG_CONFIG_HOME: '/mnt/pvc/config',
    });

    expect(env.XDG_CONFIG_HOME).toBeUndefined();
  });

  it('ENV-07: 탈출구는 지정한 이름만 추가로 통과시킨다', () => {
    const { env } = buildOpenCodeChildEnv({
      PATH: '/usr/bin',
      CORP_TOOLCHAIN_ROOT: '/opt/corp',
      OTHER: 'no',
      [EXTRA_ENV_VAR]: ' CORP_TOOLCHAIN_ROOT , ',
    });

    expect(env.CORP_TOOLCHAIN_ROOT).toBe('/opt/corp');
    expect(env.OTHER).toBeUndefined();
    // 탈출구 변수 자체는 자식에게 갈 이유가 없다.
    expect(env[EXTRA_ENV_VAR]).toBeUndefined();
  });

  it('ENV-08: 탈출구로도 차단 목록은 뚫리지 않는다 — 뚫리면 allowlist 로 뒤집은 의미가 없다', () => {
    const { env, rejectedExtras } = buildOpenCodeChildEnv({
      PATH: '/usr/bin',
      ANTHROPIC_API_KEY: 'sk-platform',
      [EXTRA_ENV_VAR]: 'ANTHROPIC_API_KEY',
    });

    expect(env.ANTHROPIC_API_KEY).toBeUndefined();
    expect(rejectedExtras).toEqual(['ANTHROPIC_API_KEY']);
  });

  it('ENV-09: 값이 undefined 인 항목은 키 자체를 만들지 않는다', () => {
    const { env } = buildOpenCodeChildEnv({ PATH: '/usr/bin', HOME: undefined });

    expect('HOME' in env).toBe(false);
  });
});

describe('parseExtraEnvNames', () => {
  it('ENV-10: 빈 값·공백·미지정을 모두 빈 목록으로 다룬다', () => {
    expect(parseExtraEnvNames(undefined)).toEqual({ allowed: [], rejected: [] });
    expect(parseExtraEnvNames('  ,, ')).toEqual({ allowed: [], rejected: [] });
  });
});
