# Branch rulesets

GitHub repository rulesets, kept in the repository so the branch policy is reviewed like code. GitHub
does not read these files by itself: apply them once, and again after every change, with
`scripts/github/apply-branch-rulesets.sh`, or import them in **Settings → Rules → Rulesets →
New ruleset → Import a ruleset**.

| Ruleset | Branches | Rules |
|---|---|---|
| `protected-develop-main.json` (name kept so the apply script updates the live ruleset) | `main` | cannot be deleted; no force push; changes only through a pull request with **2 approvals**; the last pusher cannot be one of the approvers; new commits dismiss earlier approvals; review threads resolved; CI jobs `backend`, `frontend`, `trace`, `secrets` (`.github/workflows/ci.yml`) must pass on a branch up to date with the base |
| `protected-develop.json` | `develop` | as `main`, **without the required CI checks** (owner 2026-10-27: a pull request to `develop` merges without a successful build; CI still runs and reports) |
| `pr-one-approval.json` | `release/**`, `hotfix/**`, `feature/rbac-module-ritesh` (the current default branch) | changes only through a pull request with **1 approval**; the last pusher cannot approve; new commits dismiss earlier approvals |

**Requester is never the approver.** GitHub never counts a pull request author's own review, and
`require_last_push_approval` also stops whoever pushed the last commit from approving it.

**Which branches need one approval.** A pull-request rule also forbids direct pushes to the branches it
covers, so it is not applied to every branch (developers push their own feature branches). Add a
pattern to `pr-one-approval.json` for each shared branch that receives pull requests.

**Required checks.** The four contexts are the job names of `ci.yml`, which runs on every pull
request. Keep that trigger: a required check that never reports blocks the merge. If a job is
renamed, rename it here too.

**Applying.**

```bash
GITHUB_TOKEN=<admin token> scripts/github/apply-branch-rulesets.sh --dry-run   # shows create / update
GITHUB_TOKEN=<admin token> scripts/github/apply-branch-rulesets.sh
```

A fine-grained token needs *Administration: read and write* on the repository. Rulesets are enforced
on public repositories and on private repositories of GitHub Pro, Team or Enterprise plans.
The required checks apply to `main` only. Nobody bypasses them (`bypass_actors` is empty); add the repository-admin role there only if an
emergency path is agreed.
