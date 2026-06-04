# Agentic Workflow

Bot Manager features are delivered by a chain of purpose-specific Claude Code agents. Each agent has a narrow mandate, writes its output as a file in the repo, and hands off to the next agent. The orchestrator (the user, in conversation) decides when to invoke each role. Files are durable; transcripts are not — every consequential decision lives on disk.

---

## Roles

### 1. Architect

**Purpose:** Translate a feature request into an actionable, verifiable plan.

**Input:** User request + codebase state.

**Output:** `docs/plans/<FEATURE>.md` containing:
- Findings (current state)
- Per-aspect readiness / mapping
- Architecture decisions (locked-in answers)
- Phased plan
- Implementation notes / concerns
- **`## Verification` section** — explicit step-by-step checks the Releaser will run after deploy. Every plan has one. Examples: "POST `/api/v1/bot-group/<id>/start`, expect 200; then GET `/health`, expect `connectedBots > 0`." If a feature has no on-server verification beyond the universal smoke test, the section says so explicitly.

**Tools:** Read code; write `docs/plans/`. Does not edit production code.

### 2. Dev

**Purpose:** Implement one phase of a plan.

**Input:** Plan doc + target phase number.

**Output:** Commits on a feature branch (no PR — no `origin`).

**Tools:** Full code edit + git commit. No push, no force, no destructive ops without explicit approval.

### 3. QA

**Purpose:** Maintain the Java test suite. Write tests for new features as Dev implements them; keep existing tests green.

**Input:** Plan doc + current branch diff.

**Output:**
- New / updated `*Test.java` files committed on the feature branch.
- `docs/reviews/<FEATURE>/qa.md` — verdict (pass/fail), what was tested, links to test files.

**Tools:** Full code + git, `mvn test`.

### 4. Reviewer

**Purpose:** Pure code-quality review of the branch diff.

**Input:** `git diff main..<feature-branch>`.

**Output:** `docs/reviews/<FEATURE>/review.md` — findings categorized as bug / smell / style / security, each with `file:line`.

**Tools:** Read-only on diff.

### 5. Architect-2 (Compliance)

**Purpose:** Verify the diff faithfully implements the plan.

**Input:** Plan doc + branch diff.

**Output:** `docs/reviews/<FEATURE>/compliance.md` — phase-by-phase: implemented / partial / drifted / missing. One of three verdicts:

- **Pass** — diff matches plan.
- **Send back to Dev** — code drifted but the plan was correct and a correct implementation was possible.
- **Plan amended** — plan had a genuine technical oversight (e.g. assumed API X, actually Y). Architect-2 edits the plan doc to reflect reality, commits the amendment, then accepts the diff.

**Drift policy is asymmetric:** plan is the contract. Default to "send back to Dev." Plan is only amended when there's a concrete technical justification, not preference drift.

**Tools:** Read-only on code; write `docs/plans/<FEATURE>.md` (only for amendments).

### 6. Releaser

**Purpose:** Build, ship, deploy, and verify.

**Input:** Approved feature branch + `mode=bot|infra` (default `bot`).

**Output:** `docs/reviews/<FEATURE>/release.md` — every step logged, plus results of the plan's `## Verification` section.

**Mode = bot (default):**

```bash
export JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn clean install
docker build --no-cache --platform linux/amd64 -t vingame-bot:latest .
docker save -o bot.tar vingame-bot:latest
sftp Bot-1:/home/sgame/bot-java <<< "put bot.tar"
ssh Bot-1 '
  cd /home/sgame/bot-java &&
  docker compose down &&
  (docker image rm vingame-bot:latest || true) &&
  docker load -i bot.tar &&
  docker compose up -d
'
```

**Mode = infra (rare):** the user builds `infra-images.tar.gz` themselves. Releaser sftps that file alongside `bot.tar` and runs `docker load -i infra-images.tar.gz` on the server before the rest of the deploy.

**Universal smoke test** (every release):

```bash
ssh Bot-1 'docker ps --filter name=bot-manager --format "{{.Status}}"'
#   expect: "Up X minutes (healthy)" after the start_period (~60s)

ssh Bot-1 'docker logs --tail 200 <bot-manager-container> 2>&1 | grep -E "Started Starter|startup complete"'
#   expect: Spring Boot ready line + Bot Manager startup completion line
```

**Plan-driven verification:** then execute every step from the plan's `## Verification` section. Record each step's result in `release.md`.

**Tools:** Bash (mvn, docker, ssh, sftp, curl), Read.

---

## Flow

```
User request
    │
    ▼
Architect  ─►  docs/plans/<FEATURE>.md  (includes ## Verification)
    │
    ▼  [user approves plan]
    │
Dev  ─►  commits on feature branch
    │
    ├──►  QA          ─►  *Test.java  +  docs/reviews/<F>/qa.md
    ├──►  Reviewer    ─►  docs/reviews/<F>/review.md
    └──►  Architect-2 ─►  docs/reviews/<F>/compliance.md   (or amended plan)
    │
    ▼  [user approves]
    │
Releaser  ─►  docs/reviews/<F>/release.md
    │           (universal smoke + plan-driven verification)
    ▼
Done
```

QA, Reviewer, and Architect-2 run in parallel after Dev. Releaser runs last.

---

## Artifact Layout

```
docs/
├── plans/
│   ├── OBSERVABILITY.md
│   └── <FEATURE>.md
├── process/
│   └── AGENTIC_WORKFLOW.md      ← this file
└── reviews/
    └── <FEATURE>/
        ├── qa.md
        ├── review.md
        ├── compliance.md
        └── release.md
```

---

## Approval Gates

Two human gates by default:

1. **After Architect's plan** — misalignment is cheapest to fix here.
2. **Before Releaser** — staging deploys are where caution earns its keep.

Everything between runs without explicit approval, but every step writes to a file the user can read at any time. The orchestrator can pull a thread out of the chain whenever it wants.

---

## Drift Policy (asymmetric)

- **Default:** plan is the contract. If Dev's output deviates from the plan, fix the output — not the plan.
- **Exception:** Architect-1 made a technical mistake (assumed API X, actually Y; missed a real constraint). Architect-2 amends the plan with a clear note describing the oversight, and the diff is accepted.
- **When uncertain, return to Dev.** Plan amendments require concrete technical justification, never preference.

---

## Open Items

- Quality gates: explicit pass criteria for QA / Reviewer / Architect-2 outputs (TBD).
- Agent definition files (`.claude/agents/*.md` per role) — not in this doc; to be drafted next.
