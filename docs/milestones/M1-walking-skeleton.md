# M1 — Walking Skeleton: Step-by-step Plan

| | |
|---|---|
| Milestone | M1 (see [PLAN.md §5](../PLAN.md#5-milestones)) |
| Budget | 8–12 hours |
| Prerequisite | `m0` tagged; embedding dimension locked in ADR-0006 |
| Goal | A thin, production-shaped slice through every layer: `docker compose up` → log in → create and list projects. **Every quality gate enforcing in CI from day one.** |
| Not in M1 | Ingestion, parsing, any AI. The `job` table exists, but no worker yet. |

---

## Progress tracker

| # | Step | PR | Time | Done |
|---|---|---|---|---|
| 1 | Scaffold the backend | `chore/backend-scaffold` | 45 min | ☐ |
| 2 | Module skeleton and package conventions | `chore/backend-scaffold` | 30 min | ☐ |
| 3 | Quality gates (format, lint, null-safety, arch, coverage) | `chore/quality-gates` | 90 min | ☐ |
| 4 | Testcontainers base and first migration | `feat/db-baseline` | 45 min | ☐ |
| 5 | Cross-cutting web concerns (errors, correlation ID, logging, clock) | `feat/web-foundation` | 60 min | ☐ |
| 6 | Identity: session login, CSRF, seeded user | `feat/identity-session-auth` | 90 min | ☐ |
| 7 | Project module and `ProjectAccessPolicy` | `feat/project-crud` | 90 min | ☐ |
| 8 | OpenAPI spec as a committed contract | `feat/openapi-contract` | 30 min | ☐ |
| 9 | Scaffold the frontend | `chore/frontend-scaffold` | 60 min | ☐ |
| 10 | Frontend: login and projects | `feat/fe-login-projects` | 90 min | ☐ |
| 11 | Docker Compose stack | `chore/compose-stack` | 60 min | ☐ |
| 12 | CI pipeline | `ci/github-actions` | 45 min | ☐ |
| 13 | Close out M1 | — | 15 min | ☐ |

Steps 3 and 12 can be merged before step 4, so every later PR runs through the gates. That's the recommended order: 1 → 2 → 3 → 12 → 4 → … .

---

## Step 1 — Scaffold the backend (45 min)

1. Generate it from start.spring.io: either the web UI or the command below. Use the **current Spring Boot 4.x GA** and Java 25.
   ```powershell
   cd "D:\Professional Profiles\Projects\devmind-ai"
   $deps = "web,security,validation,actuator,data-jpa,jdbc,flyway,postgresql,modulith,testcontainers,configuration-processor"
   Invoke-WebRequest "https://start.spring.io/starter.zip?type=maven-project&language=java&javaVersion=25&groupId=com.devmind&artifactId=devmind-backend&name=devmind&packageName=com.devmind&dependencies=$deps" -OutFile backend.zip
   Expand-Archive backend.zip -DestinationPath backend; Remove-Item backend.zip
   ```
   If an ID is rejected, pick the dependency in the web UI instead. Check that `flyway-database-postgresql` is present in `pom.xml`; it's required for Postgres with modern Flyway.
2. Add these dependencies by hand, with versions pinned in `<properties>`:
   - `org.springdoc:springdoc-openapi-starter-webmvc-ui` (a version compatible with your Boot line)
   - `com.pgvector:pgvector` (pgvector JDBC types, used from M3)
   - `org.jspecify:jspecify`
   - test: `com.tngtech.archunit:archunit-junit5`, `org.awaitility:awaitility`
3. Rename the main class to `DevMindApplication`, and delete the generated sample test once step 4 replaces it.
4. Check that `./mvnw -v` and `./mvnw -q -DskipTests package` work.

**Done when:** the backend compiles with the wrapper.

## Step 2 — Module skeleton and package conventions (30 min)

Create the M1 modules ([architecture §3.2–3.3](../architecture.md#32-modules-spring-modulith-application-modules)):
```
com.devmind
├── DevMindApplication.java
├── shared/            package-info.java  (value types: ids, paging, problem codes)
├── identity/          package-info.java  (public: CurrentUser, UserId)
│   └── internal/{domain,application,persistence,web}
├── project/           package-info.java  (public: ProjectApi, ProjectAccessPolicy, ProjectId)
│   └── internal/{domain,application,persistence,web}
└── job/               package-info.java  (public: JobId; worker arrives in M2)
    └── internal/...
```
Every package, including `internal` sub-packages, gets a `package-info.java`:
```java
@NullMarked
package com.devmind.project;

import org.jspecify.annotations.NullMarked;
```
Mark `shared` as an open module so every module can use it:
```java
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
@NullMarked
package com.devmind.shared;
```

**Done when:** the packages exist with `package-info.java` files, and nothing in `internal` is referenced across modules.

## Step 3 — Quality gates (90 min)

Add each gate to `backend/pom.xml`, bound to the `verify` phase:

| Gate | Plugin / tool | Configuration notes | Rules |
|---|---|---|---|
| Format | `spotless-maven-plugin` | Choose **palantir-java-format** (4-space, matches `.editorconfig`) or google-java-format, once. `spotless:check` on verify, with `spotless:apply` for local use. Also format `pom.xml`, `*.sql` (optional), `*.md` (optional). | JAVA-1 |
| Null-safety + bug patterns | `maven-compiler-plugin` + Error Prone + NullAway | Follow Error Prone's Maven installation guide: annotation processor paths, `-XDcompilePolicy=simple`, `-Xplugin:ErrorProne -XepOpt:NullAway:OnlyNullMarked=true`, and the `--add-exports` lines in `.mvn/jvm.config`. Make NullAway an error. | JAVA-2, JAVA-8 |
| Style extras | `maven-checkstyle-plugin` | Small custom config: no `System.out`, no star imports, `TypeName` regex banning `*Util|*Helper|*Manager`, `TODO` must match `TODO\(#\d+\)`. | GEN-4/5, JAVA-14 |
| Banned deps | `maven-enforcer-plugin` | `bannedDependencies` excludes `org.projectlombok:*` and `com.h2database:*`; `requireJavaVersion [25,)`; `dependencyConvergence`. | JAVA-5, TEST-4 |
| Coverage | `jacoco-maven-plugin` | `prepare-agent` + `report` + `check`. Add package rules (≥ 0.80 line) for `com.devmind.indexing.internal*`, `retrieval.internal*`, `qa.internal*`, `impact.internal*`. They pass trivially until those packages exist. | TEST-8 |
| Compiler | `maven-compiler-plugin` | `<release>25</release>`, `-parameters`, `-Werror` for javac lint (`-Xlint:all,-processing`), no `--enable-preview`. | JAVA-13 |

Then add the architecture tests in `backend/src/test/java/com/devmind/architecture/`:
```java
class ModularityTest {
  static final ApplicationModules MODULES = ApplicationModules.of(DevMindApplication.class);

  @Test void modulesRespectBoundaries() { MODULES.verify(); }                  // ARCH-2, ARCH-4

  @Test void writeModuleDocumentation() {                                     // optional, for README
    new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
  }
}
```
```java
@AnalyzeClasses(packages = "com.devmind", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest static final ArchRule noFieldInjection =
      noFields().should().beAnnotatedWith(Autowired.class);                     // JAVA-6
  @ArchTest static final ArchRule noClockBypass =
      noClasses().should().callMethod(Instant.class, "now")
          .orShould().callMethod(LocalDateTime.class, "now");                   // JAVA-9
  @ArchTest static final ArchRule noRuntimeExec =
      noClasses().should().callMethod(Runtime.class, "exec", String.class);     // SEC-4 (add other overloads)
  @ArchTest static final ArchRule domainIsPure =
      noClasses().that().resideInAPackage("..internal.domain..")
          .should().dependOnClassesThat().resideInAnyPackage(
              "..internal.web..", "..internal.persistence..",
              "org.springframework.web..", "org.springframework.jdbc..");        // ARCH-5
  @ArchTest static final ArchRule aiSdkConfined =
      noClasses().that().resideOutsideOfPackage("com.devmind.ai.internal..")
          .should().dependOnClassesThat().resideInAPackage("org.springframework.ai..");  // ARCH-8
  @ArchTest static final ArchRule controllersOnlyInWeb =
      classes().that().areAnnotatedWith(RestController.class)
          .should().resideInAPackage("..internal.web..");                       // API-1
}
```
Keep this class as the **single home for every `[arch]` rule** (TEST-10). Add a rule whenever the standards gain one.

**Done when:** `./mvnw verify` runs every gate, and a deliberate violation fails it. Try a `@Autowired` field and a `System.out`, then remove them.

## Step 4 — Testcontainers base and first migration (45 min)

1. In `backend/src/test/java/com/devmind/support/TestcontainersConfiguration.java`:
   ```java
   @TestConfiguration(proxyBeanMethods = false)
   public class TestcontainersConfiguration {
     static final DockerImageName PGVECTOR =
         DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"); // same tag as compose (TEST-4)

     @Bean @ServiceConnection
     PostgreSQLContainer<?> postgres() { return new PostgreSQLContainer<>(PGVECTOR).withReuse(true); }
   }
   ```
   Enable reuse locally with `testcontainers.reuse.enable=true` in `%UserProfile%\.testcontainers.properties`. CI doesn't reuse containers.
2. Create a composed annotation `@IntegrationTest` (`@SpringBootTest` + `@Import(TestcontainersConfiguration.class)` + `@ActiveProfiles("test")`).
3. Write `backend/src/main/resources/db/migration/V1__baseline.sql`:
   ```sql
   CREATE EXTENSION IF NOT EXISTS vector;
   CREATE EXTENSION IF NOT EXISTS pg_trgm;
   CREATE EXTENSION IF NOT EXISTS citext;

   CREATE TABLE app_user (
     id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
     public_id     uuid        NOT NULL UNIQUE,
     email         citext      NOT NULL UNIQUE,
     display_name  text        NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 100),
     password_hash text        NOT NULL,
     created_at    timestamptz NOT NULL
   );

   CREATE TABLE project (
     id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
     public_id       uuid        NOT NULL UNIQUE,
     owner_id        bigint      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
     name            text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
     source_type     text        NOT NULL CHECK (source_type IN ('LOCAL','GIT_PUBLIC')),
     source_location text        NOT NULL CHECK (char_length(source_location) <= 500),
     default_branch  text,
     created_at      timestamptz NOT NULL,
     CONSTRAINT project_owner_name_uk UNIQUE (owner_id, name)    -- also serves as the owner_id FK index
   );

   CREATE TABLE job (
     id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
     public_id       uuid        NOT NULL UNIQUE,
     project_id      bigint      NOT NULL REFERENCES project(id) ON DELETE CASCADE,
     type            text        NOT NULL CHECK (type IN ('INDEX','ANALYSIS','EVAL')),
     idempotency_key text        NOT NULL,
     status          text        NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
     payload         jsonb       NOT NULL DEFAULT '{}'::jsonb,
     attempts        int         NOT NULL DEFAULT 0,
     max_attempts    int         NOT NULL DEFAULT 3,
     run_after       timestamptz NOT NULL,
     locked_by       text,
     locked_until    timestamptz,
     progress        jsonb       NOT NULL DEFAULT '{}'::jsonb,
     error_code      text,
     error_detail    text,
     created_at      timestamptz NOT NULL,
     updated_at      timestamptz NOT NULL
   );
   CREATE UNIQUE INDEX job_idempotency_uk ON job (project_id, type, idempotency_key) WHERE status <> 'FAILED';
   CREATE INDEX job_claim_idx ON job (status, run_after);
   ```
4. Write a **schema test** (DB-2) that queries `pg_catalog` for foreign keys without a supporting index and asserts none exist. It protects every future migration.
5. Write a context test asserting `spring.jpa.open-in-view=false` and `ddl-auto=validate` (SPRING-3).

**Done when:** `./mvnw verify` starts pgvector in Docker, Flyway applies V1, and the schema and context tests pass.

## Step 5 — Cross-cutting web concerns (60 min)

| Item | Implementation | Test |
|---|---|---|
| `Clock` bean | `@Bean Clock clock() { return Clock.systemUTC(); }` in `shared`; tests override it with `Clock.fixed(...)` | — |
| Correlation ID | `OncePerRequestFilter`, highest precedence: read `X-Correlation-Id` (validate `^[A-Za-z0-9-]{8,64}$`) or generate a UUID, put it in MDC, echo it in the response header, clear it in `finally` | header echoed; generated when absent; invalid value replaced |
| Errors | `@RestControllerAdvice` producing `ProblemDetail` with `type = "https://devmind.dev/problems/<code>"`, `title`, `status`, `detail`, plus properties `code`, `correlationId`, and `errors[]` for validation. Map: validation → 400, `NotFound` → 404, `Conflict` → 409, unexpected → 500 with a generic detail (log at ERROR) | each mapping; no stack trace in the body (API-3) |
| Structured logs | `logging.structured.format.console: ecs` (or `logstash`) in `prod`/`local`, plain text in `test` | — |
| Typed config | `@ConfigurationProperties("devmind.<module>")` records with `@Validated`, registered via `@ConfigurationPropertiesScan` | binding test with an invalid value fails startup |
| Actuator | expose `health,info,metrics`; `management.server.port: 8081` in `prod` | `/actuator/env` not exposed (SPRING-5) |

`application.yaml` baseline:
```yaml
spring:
  application.name: devmind
  jpa: { open-in-view: false, hibernate.ddl-auto: validate }
  threads.virtual.enabled: true
  servlet.multipart.enabled: false
server:
  error.include-stacktrace: never
  tomcat.max-swallow-size: 1MB
management.endpoints.web.exposure.include: health,info,metrics
```
Profiles: `application-local.yaml` (datasource `localhost:5432`, debug logging for `com.devmind`), `application-test.yaml`, `application-prod.yaml` (everything from env vars). There are **no secrets in any file** (SPRING-4).

**Done when:** a request to an unknown endpoint returns a ProblemDetail with the correlation ID, and every test passes.

## Step 6 — Identity: session login, CSRF, seeded user (90 min)

1. **Entity and repository:** `AppUser` (JPA, `identity.internal.persistence`) and `AppUserRepository`.
2. **UserDetailsService** backed by the repository. Use `PasswordEncoderFactories.createDelegatingPasswordEncoder()`, which defaults to bcrypt (SEC-7).
3. **SecurityFilterChain:**
   - `authorizeHttpRequests`: permit `POST /api/auth/login`, `GET /api/auth/csrf`, `/actuator/health`; deny `/actuator/**` otherwise; authenticate `/api/**`.
   - CSRF for SPAs: cookie repository readable by JS (`CookieCsrfTokenRepository.withHttpOnlyFalse()`) plus the SPA token request handler. Follow the "Single-Page Applications" section of the Spring Security reference for your version; newer versions offer a `csrf.spa()` shortcut.
   - `exceptionHandling`: return **401 ProblemDetail** for unauthenticated API calls, not a redirect.
   - Session: `sessionFixation().changeSessionId()`. Cookie settings `server.servlet.session.cookie.{http-only:true, secure:true (prod), same-site:lax}`.
   - Disable `formLogin` and `httpBasic`. Login is a JSON endpoint.
4. **Auth endpoints** (`identity.internal.web.AuthController`):
   - `POST /api/auth/login {email, password}`: authenticate via `AuthenticationManager`, save the `SecurityContext` with `HttpSessionSecurityContextRepository`, return `{userId, email, displayName}`.
   - `POST /api/auth/logout`: invalidate the session, return 204.
   - `GET /api/auth/me`: return the current user, or 401.
   - `GET /api/auth/csrf`: touch the token so the cookie is issued.
5. **`CurrentUser`** (public API of `identity`): a request-scoped accessor returning `UserId`, for other modules to use.
6. **Seed user:** a `local`-profile `ApplicationRunner` that creates `demo@devmind.local` if absent, with the password from env `DEVMIND_SEED_PASSWORD`. If the variable is missing, fail fast. There's no seed in `test`; tests create their own users.
7. **Tests:**
   - Login success and failure (no account enumeration: identical errors).
   - Mutating request without the CSRF header → 403.
   - Session ID changes after login.
   - `/api/projects` anonymous → 401 ProblemDetail.

**Done when:** you can log in with curl or HTTPie using the CSRF cookie and header, and `/api/auth/me` returns the user.

## Step 7 — Project module and `ProjectAccessPolicy` (90 min)

1. **Public API** (`com.devmind.project`):
   ```java
   public record ProjectId(UUID value) {}
   public interface ProjectAccessPolicy {
     /** Returns the internal id of the project if `user` owns it; otherwise throws NotFound (never Forbidden, API-8). */
     long requireOwner(UserId user, ProjectId project);
   }
   public interface ProjectApi { ProjectSummary get(UserId user, ProjectId id); }
   ```
2. **Internals:**
   - `Project` entity; `ProjectService` with `create`, `listOwn(page)`, `get`, `delete` (the delete cascade is tested in M6).
   - `ProjectController`: `POST /api/projects` → 201 + `Location`; `GET /api/projects?page&size` (size ≤ 100, sort whitelist `name|createdAt`); `GET/DELETE /api/projects/{projectId}`.
   - DTOs: `CreateProjectRequest(@NotBlank @Size(max=100) name, @NotNull SourceType sourceType, @NotBlank @Size(max=500) sourceLocation)`, `ProjectResponse(...)`.
   - `sourceLocation` gets only **syntax** validation here. Real path and URL validation is ingestion's job in M2 (SEC-2/3).
3. **Authorization test matrix (TEST-9)**, started now and extended in every later milestone:
   ```java
   @IntegrationTest @AutoConfigureMockMvc
   class AuthorizationMatrixTest {
     // 1. Enumerate all handler methods from RequestMappingHandlerMapping.
     // 2. Each must be classified in a static map: PUBLIC, AUTHENTICATED, or OWNER_SCOPED(pathVar).
     // 3. Fail if any mapping is unclassified.
     // 4. For OWNER_SCOPED: owner → 2xx/202, other user → 404, anonymous → 401.
   }
   ```
4. **Tests:** create/list/get/delete for the owner; a different user gets **404** on get/delete; validation 400s; duplicate name → 409.

**Done when:** the matrix test passes and fails if you add an unclassified endpoint (try it).

## Step 8 — OpenAPI spec as a committed contract (30 min)

1. springdoc config: `springdoc.api-docs.path=/v3/api-docs`. Group only `/api/**`.
2. `OpenApiContractTest` (an integration test): fetch `/v3/api-docs` via MockMvc, pretty-print it, and compare it with `backend/openapi.json`. If the system property `updateOpenApi=true` is set, write the file instead.
   ```powershell
   ./mvnw -q test -Dtest=OpenApiContractTest -DupdateOpenApi=true   # regenerate after API changes
   ```
3. Commit `backend/openapi.json`. CI fails when it's stale (API-10).

**Done when:** changing a DTO without regenerating fails the build.

## Step 9 — Scaffold the frontend (60 min)

```powershell
cd "D:\Professional Profiles\Projects\devmind-ai"
ng new frontend --routing --style=scss --ssr=false --strict --prefix=dm --skip-git
cd frontend
ng add angular-eslint
npm i -D prettier eslint-config-prettier openapi-typescript
npm i primeng @primeuix/themes primeicons
```
1. **Zoneless:** confirm `provideZonelessChangeDetection()` in `app.config.ts`, and remove `zone.js` if present (FE-2). Recent Angular versions default to zoneless; check what was generated.
2. **`tsconfig.json`:** add `"noUncheckedIndexedAccess": true`, `"exactOptionalPropertyTypes": true`. Make sure `strictTemplates` is on (FE-1).
3. **ESLint:** angular-eslint recommended + template accessibility, `@typescript-eslint/no-explicit-any: error`, `@angular-eslint/prefer-on-push-component-change-detection: error`. Add a boundaries rule (`eslint-plugin-boundaries` or `no-restricted-imports` patterns) so `features/*` can't import other features (FE-5). Ban `bypassSecurityTrust*` and `[innerHTML]` (SEC-9).
4. **Prettier:** `.prettierrc` (`singleQuote`, `printWidth: 120`), `format` and `format:check` scripts.
5. **Folders:**
   ```
   src/app/
     core/      api/schema.d.ts (generated), auth/, http/ (interceptors), error/
     shared/    ui/ (presentational components), pipes/
     features/  projects/, explorer/ (M2), chat/ (M4), impact/ (M5), evaluation/ (M6)
   ```
6. **API types:** add the script `"api:types": "openapi-typescript ../backend/openapi.json -o src/app/core/api/schema.d.ts"`, and run it.
7. **Dev proxy** `proxy.conf.json` (wired into `ng serve` via `angular.json`), which gives a same-origin cookie flow locally:
   ```json
   { "/api": { "target": "http://localhost:8080", "secure": false } }
   ```
8. **PrimeNG:** `providePrimeNG({ theme: { preset: Aura } })` (or the preset your version ships). Check PrimeNG supports your Angular major (ADR-0014).
9. **Unit test runner:** keep the CLI default for your version (Vitest in recent versions).

**Done when:** `npm run lint`, `npm test`, `npm run build` and `npm run format:check` all pass on the empty app.

## Step 10 — Frontend: login and projects (90 min)

1. **`core/http`:**
   - `correlationIdInterceptor` adds `X-Correlation-Id` (a UUID per request).
   - `errorInterceptor`: 401 → navigate to `/login`; otherwise parse the ProblemDetail and push a toast via `ErrorNotifier`.
   - Configure them in `provideHttpClient(withInterceptors([...]), withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }))`.
2. **`core/auth`:**
   - `AuthStore` with signals `user = signal<User | null>(null)` and `isAuthenticated = computed(...)`, plus methods `login`, `logout`, `loadMe`.
   - On app start, call `GET /api/auth/csrf` and then `GET /api/auth/me`.
   - `authGuard` (functional `CanActivateFn`).
3. **`features/projects`** (lazy route):
   - `projects.api.ts`, typed from `schema.d.ts` (FE-7).
   - `projects.store.ts` (signals: `projects`, `loading`, `error`).
   - `projects-page.component.ts` (container), `project-list.component.ts` and `create-project-dialog.component.ts` (presentational, inputs and outputs only, FE-6).
   - Explicit loading, empty and error states (FE-10).
4. **Login page:** a reactive form with labelled inputs. Show errors without saying whether the email exists.
5. **Tests:** `AuthStore` (login success/failure), `ProjectsStore`, `errorInterceptor` (401 → navigation).

**Done when:** with the backend on `local` and `ng serve`, you can log in, create a project and see it listed.

## Step 11 — Docker Compose stack (60 min)

1. **`backend/Dockerfile`** (multi-stage):
   - Build stage `eclipse-temurin:25-jdk` runs `./mvnw -q -DskipTests package`.
   - Runtime stage `eclipse-temurin:25-jre`, non-root user, `java -XX:MaxRAMPercentage=75 -jar app.jar`.
   - Alternative: `./mvnw spring-boot:build-image` (Buildpacks). Pick one.
2. **`frontend/Dockerfile`:** build stage `node:<LTS>` runs `npm ci && npm run build`. The runtime stage is `caddy:<pinned>`, with `dist/frontend/browser` copied to `/srv` and the Caddyfile included.
3. **`deploy/Caddyfile`:**
   ```
   :80 {
     encode zstd gzip
     handle /api/* { reverse_proxy backend:8080 }
     handle { root * /srv; try_files {path} /index.html; file_server }
     header { X-Content-Type-Options nosniff; Referrer-Policy no-referrer; X-Frame-Options DENY }
   }
   ```
4. **`deploy/compose.yaml`:**
   ```yaml
   name: devmind
   services:
     db:
       image: pgvector/pgvector:pg17          # pin the exact tag used in tests
       environment: { POSTGRES_DB: devmind, POSTGRES_USER: devmind, POSTGRES_PASSWORD: "${DB_PASSWORD}" }
       volumes: [ "dbdata:/var/lib/postgresql/data" ]
       healthcheck: { test: ["CMD-SHELL","pg_isready -U devmind"], interval: 5s, retries: 20 }
       ports: [ "127.0.0.1:5432:5432" ]       # local dev access only
     backend:
       build: ../backend
       environment:
         SPRING_PROFILES_ACTIVE: prod
         SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/devmind
         SPRING_DATASOURCE_USERNAME: devmind
         SPRING_DATASOURCE_PASSWORD: "${DB_PASSWORD}"
         DEVMIND_AI_OLLAMA_BASE_URL: http://host.docker.internal:11434
       depends_on: { db: { condition: service_healthy } }
     web:
       build: ../frontend
       ports: [ "127.0.0.1:8088:80" ]
       depends_on: [ backend ]
   volumes: { dbdata: {} }
   ```
5. **`deploy/.env.example`:** list `DB_PASSWORD`, `DEVMIND_SEED_PASSWORD` and `DEVMIND_LLM_API_KEY` (used from M4) with placeholder values. Copy it to `deploy/.env`, which git ignores.
6. For a seeded user in compose, use a `demo` profile (`SPRING_PROFILES_ACTIVE: prod,demo`) that enables the seeding runner. Generalize the step 6 runner from `local` to `local | demo`.
7. Verify with `docker compose --env-file deploy/.env -f deploy/compose.yaml up --build`, then open `http://localhost:8088`.

**Done when:** a fresh `up` gives a working login → projects flow at `localhost:8088`.

## Step 12 — CI pipeline (45 min)

`.github/workflows/ci.yml`:
```yaml
name: ci
on: { push: { branches: [main] }, pull_request: {} }
permissions: { contents: read }
jobs:
  backend:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: backend } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '25', cache: maven }
      - run: ./mvnw -B verify          # spotless, errorprone/nullaway, checkstyle, enforcer, tests (Testcontainers), jacoco
  frontend:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: frontend } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 'lts/*', cache: npm, cache-dependency-path: frontend/package-lock.json }
      - run: npm ci
      - run: npm run api:types && git diff --exit-code src/app/core/api   # generated types in sync (API-10)
      - run: npm run format:check
      - run: npm run lint
      - run: npm test -- --watch=false
      - run: npm run build
      - run: npm audit --omit=dev --audit-level=critical                  # SEC-10
  secrets:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: gitleaks/gitleaks-action@v2                                  # SEC-6, GIT-5
  commits:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: wagoid/commitlint-github-action@v6                           # GIT-2
```
Pin action versions to the current majors when you write it.

Also:
- `.github/dependabot.yml` for `maven` (/backend), `npm` (/frontend), `github-actions` (/), weekly (SEC-10).
- `.github/pull_request_template.md` containing the PR Definition of Done from [coding-standards](../coding-standards.md#dod).
- `commitlint.config.cjs` extending `@commitlint/config-conventional`, with `scope-enum` set to the module names plus `fe, ci, deps, docs, eval`.
- Update the branch protection ruleset: require the `backend`, `frontend`, `secrets` and `commits` checks.

**Done when:** a PR shows all four checks, and a PR with a bad commit message fails `commits`.

## Step 13 — Close out M1 (15 min)

### Exit checklist (from PLAN.md M1)
- [ ] `docker compose up` (fresh volume) → log in → create and list projects
- [ ] CI green with every gate enforcing; deliberate violations were shown to fail
- [ ] Authorization matrix test exists and fails on unclassified endpoints
- [ ] `backend/openapi.json` committed; frontend types generated from it
- [ ] No secrets in the repo; gitleaks green

### Wrap-up
1. Fill in the **Commands** section of `CLAUDE.md` (build, test, run locally, compose up, regenerate OpenAPI and types), and set "Current milestone" to M2.
2. Devlog entry, covering what fought you: Error Prone setup, CSRF, zoneless.
3. `git tag -a m1 -m "M1: walking skeleton, all quality gates enforcing"` and `git push origin m1`.

## Pitfalls
- **Error Prone on a new JDK:** if the compiler plugin and Error Prone versions don't support JDK 25 yet, temporarily run the null-safety gate on a JDK 21 toolchain (Maven toolchains) rather than dropping it. Note this in the devlog.
- **CSRF + SPA:** Angular only sends `X-XSRF-TOKEN` for mutating requests to **relative** URLs. Always call `/api/...`, never `http://localhost:8080/api/...`.
- **Cookies with `Secure` on plain HTTP:** in `local`, set `secure: false`. In compose over `http://localhost`, browsers accept `Secure` cookies on localhost, but verify this in your browser.
- **Testcontainers on Windows:** Docker Desktop must be running; reuse needs the user-level properties file.
