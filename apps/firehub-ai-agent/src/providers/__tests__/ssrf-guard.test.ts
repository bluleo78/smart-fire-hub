import { describe, it, expect, vi, afterEach } from 'vitest';

/**
 * {@link assertSafeCompletionTarget} 테스트.
 *
 * <p>두 그룹으로 나눈다(자바 {@code OpencodeProbeServiceTest} 와 같은 구성). (1) IP 리터럴을 쓰는
 * 그룹 — {@code dns.lookup} 이 리터럴 주소를 실제 네트워크 조회 없이 즉시 되돌리므로, 차단
 * 대역/허용 대역 판정을 결정적으로(네트워크 없이) 검증한다. (2) {@code node:dns/promises} 를
 * mock 하는 그룹 — 호스트명이 실제로 가리키는 주소(해석 결과)로 판정하는지(DNS rebinding 방어),
 * 해석이 여러 개일 때 하나라도 막히면 전부 거부하는지, 해석 실패를 어떻게 다루는지를 본다.
 */
describe('assertSafeCompletionTarget', () => {
  afterEach(() => {
    vi.resetModules();
    vi.doUnmock('node:dns/promises');
  });

  // ---------------------------------------------------------------------
  // 1. IP 리터럴 — 네트워크 없이 결정적
  // ---------------------------------------------------------------------

  describe('IP 리터럴(네트워크 없음)', () => {
    it.each([
      ['http 스킴은 거부한다', 'http://203.0.113.10/v1', /https 만 허용/],
      ['형식이 틀린 URL 은 거부한다', 'not-a-url', /형식이 올바르지 않습니다/],
      ['IPv4 루프백은 거부한다', 'https://127.0.0.1/v1', /허용되지 않은 대상 주소/],
      ['RFC1918 10.0.0.0/8 은 거부한다', 'https://10.0.0.5/v1', /허용되지 않은 대상 주소/],
      ['RFC1918 172.16.0.0/12 은 거부한다', 'https://172.16.0.1/v1', /허용되지 않은 대상 주소/],
      // /12 는 172.16~172.31 만 막는다 — 172.32 는 공인 대역이라 통과해야 한다(경계 확인).
      ['RFC1918 192.168.0.0/16 은 거부한다', 'https://192.168.1.1/v1', /허용되지 않은 대상 주소/],
      ['링크로컬 169.254.0.0/16(클라우드 메타데이터 대역)은 거부한다', 'https://169.254.169.254/v1', /허용되지 않은 대상 주소/],
      ['CGNAT 100.64.0.0/10 은 거부한다', 'https://100.64.0.1/v1', /허용되지 않은 대상 주소/],
      ['예약된 0.0.0.0/8 은 거부한다(0.0.0.0 자체가 아니어도)', 'https://0.0.0.1/v1', /허용되지 않은 대상 주소/],
      ['멀티캐스트 224.0.0.0/4 는 거부한다', 'https://224.0.0.1/v1', /허용되지 않은 대상 주소/],
      ['IPv6 루프백은 거부한다', 'https://[::1]/v1', /허용되지 않은 대상 주소/],
      ['IPv6 미지정 주소는 거부한다', 'https://[::]/v1', /허용되지 않은 대상 주소/],
      ['IPv6 유니크로컬 fc00::/7 은 거부한다', 'https://[fd00::1]/v1', /허용되지 않은 대상 주소/],
      ['IPv6 링크로컬 fe80::/10 은 거부한다', 'https://[fe80::1]/v1', /허용되지 않은 대상 주소/],
      ['IPv6 멀티캐스트 ff00::/8 은 거부한다', 'https://[ff02::1]/v1', /허용되지 않은 대상 주소/],
      [
        'IPv4-매핑 IPv6(::ffff:사설IP)은 매핑된 IPv4 기준으로 거부한다',
        'https://[::ffff:10.0.0.5]/v1',
        /허용되지 않은 대상 주소/,
      ],
    ])('%s', async (_label, url, expectedMessage) => {
      const { assertSafeCompletionTarget } = await import('../ssrf-guard.js');
      await expect(assertSafeCompletionTarget(url)).rejects.toThrow(expectedMessage);
    });

    /**
     * 재검토 N3 — 포트 허용목록. 자바 {@code OpencodeProbeService.ALLOWED_PORTS} 가 443/8443 만
     * 허용하는데 TS 가드에는 대응 검사가 없었다(주석만 "자바와 같은 기준"이라 적혀 있었다).
     * Fix1 이전에 저장된 행은 검증 없이 들어온 baseURL 을 갖고 있을 수 있어, 런타임에서 임의
     * 포트가 열려 있으면 이 호출이 내부망 포트 스캐너가 된다.
     *
     * <p>호스트는 전부 공인 IP 리터럴이다 — 포트 검사만 단독으로 재려면 대역 판정이 통과해야
     * 한다(그래야 "주소 때문에 막혔다"와 구분된다).
     */
    it.each([
      ['Redis 포트(6379)는 거부한다', 'https://203.0.113.10:6379/v1'],
      ['Postgres 포트(5432)는 거부한다', 'https://203.0.113.10:5432/v1'],
      ['SSH 포트(22)는 거부한다', 'https://203.0.113.10:22/v1'],
      ['http 기본 포트(80)도 거부한다', 'https://203.0.113.10:80/v1'],
      ['8080 은 거부한다(8443 과 헷갈리기 쉬운 경계)', 'https://203.0.113.10:8080/v1'],
    ])('%s', async (_label, url) => {
      const { assertSafeCompletionTarget } = await import('../ssrf-guard.js');
      await expect(assertSafeCompletionTarget(url)).rejects.toThrow(/허용되지 않은 포트/);
    });

    it.each([
      ['포트 생략(=443)은 통과한다', 'https://203.0.113.10/v1'],
      ['명시된 443 은 통과한다 — URL.port 가 빈 문자열이 되는 경계', 'https://203.0.113.10:443/v1'],
      ['8443(자체 호스팅 게이트웨이 관례)은 통과한다', 'https://203.0.113.10:8443/v1'],
    ])('%s', async (_label, url) => {
      const { assertSafeCompletionTarget } = await import('../ssrf-guard.js');
      await expect(assertSafeCompletionTarget(url)).resolves.toBeUndefined();
    });

    it.each([
      ['공인 IPv4(TEST-NET-3, RFC5737)는 통과한다', 'https://203.0.113.10/v1'],
      // /12 사설 대역의 바로 밖(172.32.x) — 172.16.0.0/12 판정이 정확한 경계인지 확인한다.
      ['172.32.0.0(사설 172.16.0.0/12 범위 밖)은 통과한다', 'https://172.32.0.1/v1'],
      // CGNAT 바로 밖(100.63.x, 100.128.x) — 100.64.0.0/10 판정 경계 확인.
      ['100.63.255.255(CGNAT 범위 밖, 하한 경계)는 통과한다', 'https://100.63.255.255/v1'],
      ['100.128.0.0(CGNAT 범위 밖, 상한 경계)는 통과한다', 'https://100.128.0.1/v1'],
      ['공인 IPv6(TEST-NET, RFC3849)는 통과한다', 'https://[2001:db8::1]/v1'],
      ['IPv4-매핑 IPv6(::ffff:공인IP)은 통과한다', 'https://[::ffff:203.0.113.10]/v1'],
    ])('%s', async (_label, url) => {
      const { assertSafeCompletionTarget } = await import('../ssrf-guard.js');
      await expect(assertSafeCompletionTarget(url)).resolves.toBeUndefined();
    });
  });

  // ---------------------------------------------------------------------
  // 2. dns.lookup mock — DNS rebinding / 다중 주소 / 해석 실패
  // ---------------------------------------------------------------------

  describe('DNS 해석 결과 기반 판정', () => {
    /**
     * 호스트명 문자열이 사설 대역처럼 안 보여도(평범한 이름) 실제로 그 이름이 가리키는 IP(해석
     * 결과)가 사설 대역이면 거부해야 한다 — 문자열 매칭만 하는 구현이면 이 테스트를 못 잡는다.
     */
    it('DNS 해석 결과가 사설 대역이면 호스트명이 평범해도 거부한다', async () => {
      vi.doMock('node:dns/promises', () => ({
        lookup: vi.fn().mockResolvedValue([{ address: '10.0.0.5', family: 4 }]),
      }));
      const { assertSafeCompletionTarget } = await import('../ssrf-guard.js');

      await expect(assertSafeCompletionTarget('https://models.rebind-test.invalid/v1')).rejects.toThrow(
        /허용되지 않은 대상 주소/,
      );
    });

    /** 여러 주소로 해석되면 전부 검사한다 — 하나라도 막히면(공격자가 하나는 공인, 하나는 사설을 섞어도) 전체를 거부한다. */
    it('여러 주소 중 하나라도 차단 대역이면 전체를 거부한다', async () => {
      vi.doMock('node:dns/promises', () => ({
        lookup: vi
          .fn()
          .mockResolvedValue([
            { address: '203.0.113.10', family: 4 },
            { address: '169.254.169.254', family: 4 },
          ]),
      }));
      const { assertSafeCompletionTarget } = await import('../ssrf-guard.js');

      await expect(assertSafeCompletionTarget('https://models.multi-addr-test.invalid/v1')).rejects.toThrow(
        /허용되지 않은 대상 주소/,
      );
    });

    it('DNS 해석이 실패하면 거부한다', async () => {
      vi.doMock('node:dns/promises', () => ({
        lookup: vi.fn().mockRejectedValue(new Error('ENOTFOUND')),
      }));
      const { assertSafeCompletionTarget } = await import('../ssrf-guard.js');

      await expect(assertSafeCompletionTarget('https://genuinely-does-not-exist.invalid/v1')).rejects.toThrow(
        /호스트를 확인할 수 없습니다/,
      );
    });

    /** 허용 경로도 확인한다 — "항상 거부"로 퇴화한 뮤턴트를 잡는다(자바 쪽 Ruling #25 주변 기록과 같은 함정). */
    it('DNS 해석 결과가 공인 주소면 통과한다', async () => {
      vi.doMock('node:dns/promises', () => ({
        lookup: vi.fn().mockResolvedValue([{ address: '203.0.113.10', family: 4 }]),
      }));
      const { assertSafeCompletionTarget } = await import('../ssrf-guard.js');

      await expect(assertSafeCompletionTarget('https://models.public-test.invalid/v1')).resolves.toBeUndefined();
    });
  });
});
