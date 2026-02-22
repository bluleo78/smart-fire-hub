# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

See also the root [CLAUDE.md](../../CLAUDE.md) for monorepo-level commands and cross-app architecture.

## Commands

```bash
pnpm dev              # Vite dev server (proxies /api to localhost:8080)
pnpm build            # TypeScript check + Vite production build
pnpm lint             # ESLint (flat config, ignores dist and src/components/ui)
pnpm typecheck        # tsc -b --noEmit
pnpm preview          # Preview production build locally
```

No test framework is configured for this package.

## Architecture

### Stack

Vite + React 19 + TypeScript, TanStack Query, React Router v7 (BrowserRouter), shadcn/ui (new-york style, Radix + Tailwind CSS v4), Zod + React Hook Form, Axios. Path alias `@/` maps to `src/`.

### Layered Structure

```
src/
  api/          # Axios API modules (one per domain: datasets, pipelines, ai, etc.)
  types/        # TypeScript interfaces matching backend DTOs
  lib/          # Utilities: validations/ (Zod schemas), api-error.ts, formatters.ts
  hooks/        # useAuth (AuthContext consumer), useRecentDatasets
    queries/    # TanStack Query hooks (one file per domain)
  components/
    ui/         # shadcn/ui primitives (auto-generated, lint-ignored)
    layout/     # AppLayout (sidebar + header + AI panel), UserNav
    ai/         # AI chat system (AIProvider, panels, message rendering)
  pages/        # Route pages organized by domain
    data/       # Dataset CRUD, categories, import, SQL query
    pipeline/   # Pipeline editor (DAG canvas), triggers, executions
    admin/      # User/role management, audit logs, AI settings, API connections
```

### Key Patterns

**API Client** (`src/api/client.ts`): Axios instance at `/api/v1` with automatic JWT Bearer injection. Response interceptor handles 401 by refreshing token via `/api/v1/auth/refresh` (HttpOnly cookie) with a queue for concurrent failed requests. Access token is stored in-memory (not localStorage).

**Authentication** (`src/hooks/AuthContext.tsx` + `auth-context-value.ts`): `AuthProvider` wraps the app, provides `useAuth()` hook with `user`, `isAdmin`, `hasRole()`, `login/signup/logout`. Route protection via `ProtectedRoute` (redirects to `/login`) and `AdminRoute` (redirects to `/`).

**Server State**: All data fetching uses TanStack Query hooks in `src/hooks/queries/`. Pattern: `useQuery` for reads, `useMutation` with `queryClient.invalidateQueries()` for writes. Polling via `refetchInterval` for active executions/imports.

**Form Validation**: Zod schemas in `src/lib/validations/` + `@hookform/resolvers`. Each domain has its own schema file.

**Error Handling**: `handleApiError()` / `extractApiError()` in `src/lib/api-error.ts` extracts backend `ErrorResponse.message` and shows via Sonner toast.

**Routing** (`src/App.tsx`): All pages are lazy-loaded with `React.lazy()` + `Suspense`. Routes nest under `ProtectedRoute > AppLayout`, admin routes additionally nest under `AdminRoute`.

**AI Chat System**: SSE streaming via raw `fetch` (not Axios) in `src/api/ai.ts`. `AIProvider` context manages chat state with three display modes: side panel, floating, fullscreen. Toggle with Cmd/Ctrl+K. Session management with compaction support.

**Pipeline Visualization**: `@xyflow/react` for DAG rendering with dagre auto-layout (`src/pages/pipeline/utils/dagre-layout.ts`). Supports SQL, Python, and API_CALL step types with CodeMirror editors.

**Code Splitting**: Manual chunks in `vite.config.ts` for CodeMirror, xyflow/dagre, and react-markdown. AI panels and page components are lazy-loaded.

### Conventions

- shadcn/ui components in `src/components/ui/` are generated via `npx shadcn` CLI; do not manually edit them
- shadcn config: new-york style, neutral base color, Lucide icons, CSS variables enabled
- Theming via `next-themes` (dark/light/system)
- Toast notifications via Sonner (`toast.success()`, `toast.error()`)
- ESLint flat config with `typescript-eslint`, `react-hooks`, `react-refresh` plugins
- Vite proxy forwards `/api` requests to backend at `localhost:8080` with SSE buffering disabled
