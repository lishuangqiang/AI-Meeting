# WebSocket security and CI implementation plan

**Goal:** Prevent cross-user push forgery and identity collisions, and restore executable CI tests.

**Design:** Protect the WebSocket REST management controller with the existing Sa-Token admin role. Authorize WebSocket connections only when the path equals the canonical numeric user ID resolved from the token. Keep token parameter compatibility. Repair stale test fixtures and run all tests in CI without external services or paid APIs.

**Compatibility:** Clients must connect to `/api/xunzhi/v1/xunfei/audio-to-text/{numericUserId}`. Username paths are rejected. REST WebSocket management calls require an administrator token; internal server-side push remains available through `WebSocketMessageService`.

**Tech stack:** Java 17, Spring MVC, Sa-Token, JUnit 5, Mockito, Maven.

## Tasks

- [x] Repair the 11 stale constructor calls in interview tests using mocks of `InterviewSessionRuntimeSnapshotService` and `InterviewSessionRuntimeRehydrateService`.
- [x] Add WebSocket auth regression tests for numeric username collisions, username paths, other users, missing tokens, lookup failures and compatible token parameters.
- [x] Add MVC tests using the real Sa-Token interceptor: anonymous and ordinary users cannot call management endpoints, admins can call each endpoint, and denied calls never reach the push service.
- [x] Run the new tests against the original production code and confirm they fail for the reported security defects.
- [x] Add `@SaCheckRole("admin")` to `WebSocketController`; remove username-path authorization from `SaTokenWebSocketAuthService`.
- [x] Remove `-Dmaven.test.skip=true` from CI. Resolve test runner setup and stale fixtures uncovered by running the full suite, preserving existing assertions.
- [x] Run `mvn -B -ntp clean verify` with JDK 17 and review the final diff.
- [x] Obtain an independent code review before publishing the branch and creating an upstream PR.

## Validation

Run the security regression tests with `mvn -pl admin -Dtest=SaTokenWebSocketAuthServiceTest,WebSocketControllerTest test`, followed by the complete `mvn -B -ntp clean verify` gate. Local Maven mirror/proxy settings are temporary and must not be committed. No online AI calls are permitted during tests.

## Results

- Before the production fixes: 26 security tests, 7 expected regression failures.
- After the fixes: 116 tests across 36 suites, no failures, errors or skipped tests.
- Full Java 17 `clean verify`, including formatting checks and executable JAR packaging, passed.
- Independent read-only review found no actionable blockers.
