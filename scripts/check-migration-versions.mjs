#!/usr/bin/env node
// Flyway 마이그레이션 번호 확인 (WP-1081 → WD-506 이식) — `node scripts/check-migration-versions.mjs <base-ref> [<head-ref>]`
// 두 PR 이 같은 번호(V<n>__)를 각자 더하면 파일 이름이 달라 git 충돌도 없고 PR 마다의 검사도 통과하지만,
// 합친 main 에서 Flyway 가 번호 중복으로 실패한다. main 의 최대 번호 이하를 늦게 머지해도 이미 적용한 DB 에서 실패한다.
// 그래서 사람·AI 기억에 맡기지 않고 아래를 기계적으로 본다(head 기본값 HEAD):
//   1) head 트리 안에서 같은 번호가 둘 이상인가
//   2) head 가 갈라진 뒤(merge-base) 더한 마이그레이션의 번호가 base 의 최대 번호 이하인가
//   3) 더한 마이그레이션이 정수가 아닌 버전(V230_1__·V230.1__)인가 — 이 저장소는 V<정수>__ 만 쓴다. 놓치지 않게 막는다
// Flyway 처럼 하위 폴더까지 본다.
// 하나라도 걸리면 번호를 적고 종료 코드 1, ref 를 읽지 못하면 2.
// 쓰는 곳: 기차 쌓기·rebase(scripts/husky/train.sh), 검토자 머지 직전(base = origin/main — .work-loop/project.md 9절).
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const MIGRATION_DIR = 'apps/firehub-api/src/main/resources/db/migration'

const VERSIONED = /^V(\d+)__.+\.sql$/
/** Flyway 가 버전 마이그레이션으로 읽는 이름(V<버전>__*.sql — 버전에 . 이나 _ 가 들어갈 수 있다). */
const ANY_VERSIONED = /^V.+?__.+\.sql$/i

/** 파일(하위 폴더 경로 가능)에서 정수 버전 번호를 꺼낸다 — V<정수>__*.sql 이 아니면 null. */
export function migrationVersion(file) {
  const m = VERSIONED.exec(path.posix.basename(file))
  return m ? Number(m[1]) : null
}

/** Flyway 버전 마이그레이션이지만 정수 번호가 아닌 파일인가(V230_1__·V230.1__·.SQL). */
function isNonIntegerVersioned(file) {
  const name = path.posix.basename(file)
  return ANY_VERSIONED.test(name) && migrationVersion(name) === null
}

/**
 * 번호 문제를 찾는다(순수 함수 — git 은 CLI 가 읽어 넘긴다).
 * @param head 검사할 트리의 마이그레이션 파일 이름들
 * @param fork head 가 갈라진 지점(merge-base)의 파일 이름들 — head 에만 있는 것이 「더한 것」
 * @param base 기준(main)의 파일 이름들 — 더한 것은 이 최대 번호보다 커야 한다
 * @returns duplicates: [{ version, files }], notAfterBase: [{ version, file }], nonInteger: [file], baseMax
 */
export function findMigrationProblems({ head, fork, base }) {
  const byVersion = new Map()
  for (const name of head) {
    const v = migrationVersion(name)
    if (v === null) continue
    if (!byVersion.has(v)) byVersion.set(v, [])
    byVersion.get(v).push(name)
  }
  const duplicates = [...byVersion]
    .filter(([, files]) => files.length > 1)
    .map(([version, files]) => ({ version, files: files.sort() }))
    .sort((a, b) => a.version - b.version)

  const baseMax = Math.max(0, ...base.map(migrationVersion).filter((v) => v !== null))
  const forkSet = new Set(fork)
  const added = head.filter((name) => !forkSet.has(name))
  const notAfterBase = added
    .map((file) => ({ version: migrationVersion(file), file }))
    .filter(({ version }) => version !== null && version <= baseMax)
    .sort((a, b) => a.version - b.version)
  const nonInteger = added.filter(isNonIntegerVersioned).sort()

  return { duplicates, notAfterBase, nonInteger, baseMax }
}

/** 문제를 사람이 읽을 줄들로 — 문제가 없으면 빈 배열. */
export function describeProblems({ duplicates, notAfterBase, nonInteger, baseMax }, baseRef) {
  const lines = []
  for (const { version, files } of duplicates) {
    lines.push(`번호 중복 V${version}: ${files.join(', ')}`)
  }
  for (const { version, file } of notAfterBase) {
    lines.push(`${baseRef} 의 최대 번호 V${baseMax} 이하: ${file} (V${version})`)
  }
  for (const file of nonInteger) {
    lines.push(`정수가 아닌 버전(V<정수>__ 만 쓴다): ${file}`)
  }
  return lines
}

function git(args) {
  return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
}

/** ref 트리의 마이그레이션 파일들(폴더 기준 상대 경로, 하위 폴더 포함) — 경로로 거르므로 그 커밋에 폴더가 없으면 빈 배열. */
function migrationFiles(ref) {
  return git(['ls-tree', '-r', '--name-only', ref, '--', `${MIGRATION_DIR}/`])
    .split('\n')
    .filter(Boolean)
    .map((p) => path.posix.relative(MIGRATION_DIR, p))
}

function main([baseRef, headRef = 'HEAD']) {
  if (!baseRef) {
    console.error('사용: node scripts/check-migration-versions.mjs <base-ref> [<head-ref>]')
    return 2
  }
  let head, fork, base
  try {
    const forkSha = git(['merge-base', baseRef, headRef])
    ;[head, fork, base] = [headRef, forkSha, baseRef].map(migrationFiles)
  } catch (e) {
    console.error(`[migration-versions] ref 를 읽지 못함(${baseRef}, ${headRef}): ${e.stderr?.trim() || e.message}`)
    return 2
  }
  const lines = describeProblems(findMigrationProblems({ head, fork, base }), baseRef)
  if (lines.length === 0) {
    console.log(`[migration-versions] 통과 — ${headRef} 의 마이그레이션 번호가 겹치지 않고 ${baseRef} 뒤에 온다`)
    return 0
  }
  console.error(
    '[migration-versions] 마이그레이션 번호를 옮겨야 한다 — 최신 main 위로 rebase 해 가장 큰 번호 뒤로 올리고 ' +
      'cd apps/firehub-api && ./gradlew generateJooq 결과(src/main/generated/)를 함께 커밋한다',
  )
  for (const l of lines) console.error(`  - ${l}`)
  return 1
}

/**
 * 이 파일을 직접 실행했는지(테스트의 import 가 아닌지) — 심볼릭 링크 경로로 불러도 검사 없이 0 으로 끝나지 않게 실제 경로로 비교한다.
 * WP 는 공용 도우미(deploy/release/lib/cli.mjs)를 쓰지만 이 저장소에는 없어 여기 둔다.
 */
function isMainModule(metaUrl) {
  if (!process.argv[1]) return false
  try {
    return fs.realpathSync(process.argv[1]) === fs.realpathSync(fileURLToPath(metaUrl))
  } catch {
    return false
  }
}

if (isMainModule(import.meta.url)) {
  process.exitCode = main(process.argv.slice(2))
}
