# Smart Fire Hub

소방 데이터를 적재하고 분석하는 플랫폼. CSV/Excel 파일 업로드를 통해 데이터를 수집하고 분석합니다.

## 기술 스택

- **Backend**: Spring Boot 3.4, Java 25, Gradle 9.3
- **Frontend**: Vite 7 + React 19 + TypeScript 5.9
- **Database**: PostgreSQL 16
- **Monorepo**: pnpm + Turborepo

## 사전 요구사항

- Java 25 (`brew install openjdk@25` 또는 [SDKMAN](https://sdkman.io/))
- Node.js 24+ (`brew install node`)
- pnpm (`corepack enable pnpm`)
- Docker & Docker Compose (PostgreSQL 실행용)

## 시작하기

```bash
pnpm install          # 전체 의존성 설치
pnpm dev:full         # DB + 전체 dev 서버 시작 (프론트엔드 + 백엔드)
```

또는 개별 실행:

```bash
pnpm db:up            # PostgreSQL 시작
pnpm dev              # 전체 dev 서버 시작
```

- 프론트엔드: http://localhost:5173
- 백엔드 API: http://localhost:8080
- `/api` 요청은 Vite 프록시로 백엔드에 전달됩니다.

## 빌드 & 테스트

```bash
pnpm build            # 전체 빌드
pnpm test             # 전체 테스트
pnpm lint             # 전체 린트
pnpm typecheck        # 전체 타입체크
```

## 개별 프로젝트 명령어

```bash
# Backend (firehub-api)
cd apps/firehub-api && ./gradlew bootRun --args='--spring.profiles.active=local'
cd apps/firehub-api && ./gradlew build
cd apps/firehub-api && ./gradlew test

# Frontend (firehub-web)
cd apps/firehub-web && pnpm dev
cd apps/firehub-web && pnpm build
cd apps/firehub-web && pnpm lint
```

## 프로젝트 구조

```
smart-fire-hub/
├── apps/
│   ├── firehub-api/      # Spring Boot API 서버
│   └── firehub-web/      # React SPA (Vite)
├── packages/             # 공유 패키지 (향후)
├── docker-compose.yml
├── turbo.json            # Turborepo 파이프라인
├── pnpm-workspace.yaml   # pnpm 워크스페이스
└── CLAUDE.md             # 개발 가이드
```
