# ADR-0010: Session auth behind a same-origin proxy; no JWT
- Status: Accepted
- Date: 2026-11-06

## Context
A single SPA served from the same origin as the API (via Caddy, with the Angular dev proxy locally). Auth isn't the portfolio highlight, but **authorization** (project isolation) is a graded requirement.

## Options considered
| Option | For | Against |
|---|---|---|
| JWT in localStorage | Common in tutorials | XSS-exfiltratable tokens; revocation is hard; more code |
| JWT in HttpOnly cookie | Better | Still need CSRF protection *and* token lifecycle; no benefit over sessions here |
| **Server session (HttpOnly cookie) + CSRF token** | Built into Spring Security, revocable, least code | Sticky or shared sessions if scaled. Spring Session JDBC solves that later. |
| OAuth2 login (GitHub) | Nice demo UX, no password storage | Extra setup; can be added on top of sessions |

## Decision
Spring Security form/JSON login with server sessions. Cookie flags `HttpOnly; Secure; SameSite=Lax`. CSRF via `CookieCsrfTokenRepository` (`XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header, which Angular's `HttpClient` handles), session fixation protection, BCrypt/Argon2 password hashing. Users are seeded in v1, with no public sign-up. GitHub OAuth2 login is optional in M6.

**Authorization:** a single `ProjectAccessPolicy.requireOwner(userId, projectId)`, called by every application service that accepts a project-scoped ID (including job/analysis lookups resolved to their project). Non-owners get **404**. An authz matrix test covers every endpoint × {owner, other user, anonymous}.

## Consequences
- \+ Less auth code, more secure defaults, and a clear story.
- − The MCP server (M7) needs a separate auth mechanism, such as a local-only binding or a static token. That's decided in ADR-0012.
