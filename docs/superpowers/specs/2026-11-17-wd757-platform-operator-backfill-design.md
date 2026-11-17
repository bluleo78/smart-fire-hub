# WD-757 운영자 없는 기존 설치본 — 조건부 SUPER_ADMIN 보정 마이그레이션

## 결정(사람, 2026-11-17 10:20 KST 양동희): 1번 조건부 보정 마이그레이션

## 문제
`platform_user_role` 이 비어 있는데 사용자가 이미 있는 설치본(V82 가 빈 DB 에서 돈 PG 설치본, V155/V155.1 기준선으로 시작한 Tibero 설치본)은 공개 가입이 닫혀 WD-748(첫 가입 SUPER_ADMIN)·WD-749(사용자 0명일 때 첫 운영자 만들기)에 닿지 않아 운영자 콘솔에 들어갈 계정이 없다.

## 바꿀 것
1. **마이그레이션 V166 두 벌** — `db/migration/V166__backfill_platform_super_admin.sql`(PG), `db/migration-tibero/V166__backfill_platform_super_admin.sql`(Tibero). 번호는 머지 직전 origin/main·다른 기차와 다시 확인(project.md 7·8절).
   - 조건: `platform_user_role` 에 행이 하나도 없을 때만.
   - 대상: 기본 테넌트(id=1, `MembershipRepository.DEFAULT_TENANT_ID`)에서 테넌트 역할 `ADMIN`(user_role → role.name='ADMIN', 같은 tenant_id)을 가진 사용자 중 계정 활성(`"user".is_active`) + 그 테넌트 멤버십 활성(`membership.status='ACTIVE'`)인 사람 가운데 가장 먼저 만든 한 명(`"user".created_at`, 같으면 id 작은 쪽).
   - 부여: 그 사용자에게 `platform_role.name='SUPER_ADMIN'`. 대상이 없거나 SUPER_ADMIN 시드가 없으면 아무것도 하지 않는다(실패하지 않음).
   - 한 명만 준다 — V82 처럼 모든 ADMIN 을 올리지 않는다(이 설치본의 ADMIN 은 테넌트 관리자 의미라, 플랫폼 권한은 최소로).
   - RLS(PG `user_role`·`membership` 의 FORCE 여부, Tibero V156.1 정책)가 마이그레이션 사용자의 조회를 거르지 않는지 확인하고, 거르면 V82·기존 백필 마이그레이션의 방식을 따른다.
2. **배포 문서** `.claude/docs/deploy.md` — 「사용자가 이미 있는 기존 설치본(V82 승격으로 SUPER_ADMIN 보유)에는 영향 없음」 문구를 고친다: V166 이 운영자가 없는 설치본에 한해 기본 워크스페이스의 가장 먼저 만든 활성 ADMIN 한 명을 SUPER_ADMIN 으로 올린다. 배포 뒤 누가 받았는지 확인하는 쿼리(PG·Tibero)와, 다른 사람이어야 하면 바꾸는 방법을 적는다.

## 시험
- PG(Testcontainers): 운영자 없음 + ADMIN 여럿(비활성·정지·다른 테넌트 섞임) → 조건에 맞는 가장 이른 한 명만 받음 / 운영자가 이미 있음 → 아무 변화 없음 / ADMIN 없음 → 변화 없음. 기존 마이그레이션 시험 패턴(Flyway target)을 따른다.
- Tibero: 기존 Tibero 마이그레이션 시험 인프라(TiberoTenantMigrationTest 등)가 이 환경에서 돌면 같은 경우를 넣고, 돌지 않으면(라이브 Tibero 필요) SQL 을 PG 와 같은 뜻으로 두고 사유를 남긴다.

## 내가 정한 것
- 「가장 먼저 만든 ADMIN」 = 기본 테넌트 user_role ADMIN + 계정·멤버십 활성, created_at·id 순. 비활성·정지 계정은 콘솔에 못 들어가므로 뺀다.
- 한 명만 부여(V82 의 「전원」과 다름) — 최소 권한. 다른 운영자는 콘솔에서 추가.
