# SSH Worker Coordination

This document is the shared protocol for agents using the SSH worker for
CoCoMUT builds, compilation-heavy repository analysis, and long field runs.

The worker is shared. Treat it as a constrained compute machine, not as a
private scratch shell.

## Access

From the laptop:

```bash
ssh worker
# or
ssh codex-worker
```

The login user is `ale`. The user can use `sudo` when a package is genuinely
missing, but prefer reusing installed Java/Maven/Git tooling.

Observed worker shape:

```text
logical CPUs: 16
memory: roughly 16 GB class
```

Always re-check live resources before starting expensive jobs:

```bash
free -h
nproc
uptime
ps -u ale -o pid,etime,pcpu,pmem,args --sort=-pcpu | head -30
```

## Per-Agent Workspace

Use one directory per agent task:

```bash
mkdir -p ~/agent-runs/<task-name>
```

Example:

```bash
mkdir -p ~/agent-runs/cocomut-call-edge-nextwork
```

Do not put unrelated task outputs into another agent's run directory.

## Ownership Marker

Before starting long jobs, create an ownership marker:

```bash
cat > ~/agent-runs/<task-name>/AGENT_PROCESS.md <<'MARKER'
owner: codex <branch-or-task>
purpose: <short reason>
started: <ISO timestamp>
cpu_slots_requested: <number>
memory_gb_requested: <number>
expected_finish: <rough time or unknown>
do_not_kill_unless: user explicitly asks or process is runaway
MARKER
```

Other agents must inspect these files before starting heavy jobs:

```bash
find ~/agent-runs -maxdepth 2 -name AGENT_PROCESS.md -print \
  -exec sed -n '1,100p' {} \;
```

## CPU Slot Policy

The worker has 16 logical CPUs. Use a simple slot model so agents can share it
without overloading it.

Recommended slot estimates:

```text
job type                                  slots
----------------------------------------  -----
single CoCoMUT extraction, no build        1-2
single Maven/Gradle compile                4
single CoCoMUT extraction with call graph  3-4
large public-repo field run                6-8
full parallel test/build sweep             8-12
```

Coordination rule:

- Keep total claimed slots at or below 12 by default.
- Leave roughly 4 CPUs free for the desktop, SSH, Maven spikes, and other
  agents.
- Use all 16 cores only after checking that no other agent has a live marker and
  the user asked for maximum throughput.

For Maven, avoid aggressive parallelism unless the machine is idle:

```bash
# conservative
./mvnw -q test

# only when enough slots are free
./mvnw -q -T 4 test
```

## Memory Policy

The worker has limited RAM. Public Java repository compilation can spike memory
through Maven, Gradle, Spoon, SootUp, annotation processors, and test plugins.

Defaults:

```bash
export MAVEN_OPTS="-Xmx3g"
export JAVA_TOOL_OPTIONS="-Xmx3g"
```

Rules:

- Do not run multiple large Maven/Gradle compilations in parallel.
- Prefer sequential repository runs unless `free -h` shows comfortable memory.
- For large repositories, use bounded CoCoMUT extraction before raising heap:
  `--max-source-files`, `--max-methods`, or `--call-graph none`.
- If memory drops below about 3-4 GB available, wait instead of starting a new
  job.

## Process Naming And Logs

Write logs under the agent run directory:

```bash
mkdir -p ~/agent-runs/<task-name>/logs
```

When possible, create a PID file:

```bash
bash -c 'echo $$ > ~/agent-runs/<task-name>/RUNNER.pid; exec ./scripts/run-task.sh' \
  > ~/agent-runs/<task-name>/logs/run.log 2>&1
```

Inspect running agent jobs with:

```bash
ps -u ale -o pid,etime,pcpu,pmem,args | \
  grep -E 'agent-runs|cocomut|mvn|gradle|java|git clone' | grep -v grep
```

Do not kill another agent's process unless:

1. the marker says it is safe;
2. the process is clearly stale or runaway;
3. the user approved killing it.

## Repository Sync Pattern

Do code edits in a local worktree first, then sync that worktree to the worker.
Avoid editing the same branch in two physical directories at once.

From the laptop:

```bash
rsync -a --delete \
  --exclude .git \
  --exclude target \
  --exclude experiments \
  --exclude 'analyzer-tests/cocomut_output' \
  /home/ale/repos/repo_mining_trials/<local-worktree>/ \
  worker:~/agent-runs/<task-name>/repo/
```

Pull results back from the worker:

```bash
rsync -a worker:~/agent-runs/<task-name>/repo/experiments/<run-name>/ \
  /home/ale/repos/repo_mining_trials/<local-worktree>/experiments/<run-name>/
```

Do not commit bulky experiment outputs unless the user explicitly asks. Most
experiment folders are ignored by Git and should remain local evidence.

## Long CoCoMUT Field Runs

For long repository sweeps:

```bash
./scripts/run_oe25_plus_representative_study.py \
  --output-dir experiments/<run-name> \
  --timeout 1200 \
  --compile-timeout 240 \
  --heap-gb 3 \
  --min-available-gb 4 \
  --max-load 10
```

Use `--repo` and `--extra-repo` for focused runs before broad sweeps:

```bash
./scripts/run_oe25_plus_representative_study.py \
  --output-dir experiments/<run-name> \
  --extra-repo zxing/zxing \
  --repo zxing/zxing
```

## Completion

At the end of a worker run:

1. copy compact results or reports back to the local worktree;
2. leave bulky JSONL/checkouts on the worker only if still needed;
3. update the local branch report/docs;
4. update or remove `AGENT_PROCESS.md` so other agents know the task is done.
