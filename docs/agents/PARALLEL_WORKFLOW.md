# Parallel Agent Workflow

Use Git worktrees when multiple Codex instances work on CoCoMUT at the same time.
A worktree gives each agent its own directory and branch while sharing the same
underlying Git repository.

## Why Worktrees

Without worktrees, parallel agents can overwrite each other's files, staged
changes, generated outputs, and branch state. With worktrees:

- each agent has an independent working directory;
- each agent has an independent branch;
- Maven target directories and temporary files stay local to that worktree;
- PRs remain smaller and easier to review.

## Create Three Parallel Worktrees

From `/home/ale/repos/repo_mining_trials`:

```bash
git -C Code-Context-Extractor fetch origin

git -C Code-Context-Extractor worktree add \
  ../cocomut-task-callgraph \
  -b task/callgraph-resolution \
  origin/main

git -C Code-Context-Extractor worktree add \
  ../cocomut-task-docs \
  -b task/docs-polish \
  origin/main

git -C Code-Context-Extractor worktree add \
  ../cocomut-task-viewer \
  -b task/viewer-ux \
  origin/main
```

Then start one Codex instance per directory:

```bash
cd /home/ale/repos/repo_mining_trials/cocomut-task-callgraph
cd /home/ale/repos/repo_mining_trials/cocomut-task-docs
cd /home/ale/repos/repo_mining_trials/cocomut-task-viewer
```

Each agent should read:

```text
AGENTS.md
docs/agents/CURRENT_STATE.md
docs/agents/PARALLEL_WORKFLOW.md
```

## Good Task Split

Good parallel tasks touch different files:

```text
Agent 1: call graph / matching logic
  likely files: analyzer-core/, analyzer-tests/, schemas/

Agent 2: documentation / README / release notes
  likely files: README.md, docs/, examples/

Agent 3: viewer / scripts / research artifact UX
  likely files: scripts/, docs/usage.md
```

Risky parallel tasks touch shared files:

```text
README.md
schemas/README.md
schemas/method-context.schema.json
MethodContext / MethodInfo / JsonGenerator
```

If two agents must touch the same files, make one branch land first, then rebase
the other branch.

## Agent Handoff Protocol

At the start of a task, the agent should record:

```text
branch name
task goal
files expected to touch
verification command
```

At the end of a task, the agent should report:

```text
commit hash
changed files
verification run
known limitations
PR link if opened
```

Do not rely on chat memory as the source of truth. The repo should contain the
important decisions.

## Keeping Branches Fresh

Before opening a PR:

```bash
git fetch origin
git rebase origin/main
./mvnw -q test
```

If the branch is already pushed:

```bash
git push --force-with-lease
```

Use `--force-with-lease`, not plain `--force`.

## Listing and Removing Worktrees

List worktrees:

```bash
git -C Code-Context-Extractor worktree list
```

Remove a finished worktree after its branch is merged:

```bash
git -C Code-Context-Extractor worktree remove ../cocomut-task-docs
git -C Code-Context-Extractor branch -d task/docs-polish
```

If Git says a worktree is stale:

```bash
git -C Code-Context-Extractor worktree prune
```

## Scaling Beyond Three Agents

Worktrees scale linearly as long as the tasks are independent. The practical
limit is coordination, not Git.

For three agents:

- keep one owner per task area;
- avoid editing the same central model/schema files in parallel;
- open small PRs quickly;
- rebase frequently.

For more than three agents:

- add a simple task board in `docs/agents/CURRENT_STATE.md`;
- assign file ownership per branch;
- require every branch to state which schema/API fields it changes;
- merge foundational model/schema branches before downstream docs/viewer
  branches.

