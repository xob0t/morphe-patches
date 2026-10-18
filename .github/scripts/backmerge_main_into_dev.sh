#!/usr/bin/env bash
#
# Merge stable main back into dev so dev always contains every released commit.
# Target promotions go directly to main and refuse to run while dev lacks main.
#
# Idempotent: exits 0 when dev already contains main. Each attempt works on a
# fresh full clone (a blob-less partial clone lazily fetches objects during the
# merge, which can fail right after a push), and transport failures or a dev
# branch that moved during the attempt are retried. Only a genuine conflict
# outside release metadata fails without retrying.
set -euo pipefail

REMOTE="${BACKMERGE_REMOTE:-https://github.com/${GITHUB_REPOSITORY:?GITHUB_REPOSITORY required}.git}"
ATTEMPTS="${BACKMERGE_ATTEMPTS:-5}"
WORK="${RUNNER_TEMP:-$(mktemp -d)}/backmerge"
# Release metadata on dev belongs to its prerelease.
METADATA=(CHANGELOG.md README.md gradle.properties patches-bundle.json patches-list.json)

# Returns 0 when dev contains main, 1 on a non-retryable failure, 2 to retry.
# Called under `||`, so errexit is off here: every step checks its own status.
attempt() {
  rm -rf "$WORK"
  # A plain clone takes main and dev from one ref advertisement.
  git clone --quiet --no-tags --branch dev "$REMOTE" "$WORK" || return 2
  git -C "$WORK" config user.name "${BACKMERGE_GIT_NAME:-morphe-target-promoter[bot]}" || return 1
  git -C "$WORK" config user.email "${BACKMERGE_GIT_EMAIL:-morphe-target-promoter[bot]@users.noreply.github.com}" || return 1

  if git -C "$WORK" merge-base --is-ancestor origin/main HEAD; then
    echo "dev already contains main ($(git -C "$WORK" rev-parse --short origin/main))."
    return 0
  fi

  if ! git -C "$WORK" merge --no-ff --no-commit origin/main; then
    local conflicts file
    conflicts=$(git -C "$WORK" diff --name-only --diff-filter=U)
    if [ -z "$conflicts" ]; then
      echo "Merge failed without file conflicts." >&2
      return 2
    fi
    while IFS= read -r file; do
      case " ${METADATA[*]} " in
        *" $file "*)
          git -C "$WORK" checkout --ours -- "$file" || return 1
          git -C "$WORK" add -- "$file" || return 1
          ;;
        *)
          echo "::error::Unexpected backmerge conflict: $file" >&2
          return 1
          ;;
      esac
    done <<< "$conflicts"
  fi

  # Restore metadata even when a merge combined part of a generated file
  # without a conflict.
  git -C "$WORK" restore --source=HEAD --staged --worktree -- "${METADATA[@]}" || return 1
  git -C "$WORK" diff --cached --check || return 1
  git -C "$WORK" commit --quiet -m 'chore: backmerge main into dev [skip ci]' || return 1
  # Rejected when dev moved since the clone; the next attempt re-merges.
  git -C "$WORK" push origin HEAD:dev || return 2
  echo "Backmerged main ($(git -C "$WORK" rev-parse --short origin/main)) into dev."
}

for ((i = 1; i <= ATTEMPTS; i++)); do
  status=0
  attempt || status=$?
  case "$status" in
    0) exit 0 ;;
    1) exit 1 ;;
  esac
  if [ "$i" -lt "$ATTEMPTS" ]; then
    delay=$((i * 15))
    echo "Backmerge attempt $i/$ATTEMPTS failed; retrying in ${delay}s." >&2
    sleep "$delay"
  fi
done
echo "::error::Backmerge failed after $ATTEMPTS attempts." >&2
exit 1
