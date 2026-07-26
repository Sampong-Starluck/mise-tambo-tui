#!/usr/bin/env bash
#
# Builds the GraalVM native image and installs it as a single self-contained
# binary on your PATH.
#
#   ./deploy-tambo.sh                    build and install
#   ./deploy-tambo.sh --clean            same, but from a clean target/
#   ./deploy-tambo.sh --mise TASK        build by running a mise task
#   PREFIX=/usr/local ./deploy-tambo.sh
#
set -euo pipefail

# Run from the repo root no matter where the script was invoked from.
cd "$(dirname "$(readlink -f "$0")")"

PREFIX="${PREFIX:-$HOME/.local}"
BIN_DIR="$PREFIX/bin"
BINARY="mise-tambo"
BUILT="target/$BINARY"
DEST="$BIN_DIR/$BINARY"
CLEAN=""
USE_MISE="" # unset until chosen: "yes" | "no"
MISE_TASK=""
DEFAULT_TASK="compile-native"

usage() {
    cat <<EOF
Usage: ${0##*/} [--clean] [--mise [TASK] | --no-mise]

Builds the native image and installs it to \$PREFIX/bin (default ~/.local/bin).

  --clean       wipe target/ first (slower; use after dependency or config changes)
  --mise [TASK] build by running a mise task (default task: $DEFAULT_TASK)
                rather than calling ./mvnw with the java on your PATH
  --no-mise     never use mise, even if this project has a mise.toml
  -h            show this help

If the project has a mise.toml and neither flag is given, you are asked which
to use. Run --mise or --no-mise explicitly when there is no terminal to ask at.

Environment:
  PREFIX    install prefix (default: \$HOME/.local)
EOF
}

die() {
    echo "error: $*" >&2
    exit 1
}

while [ $# -gt 0 ]; do
    case "$1" in
        --clean) CLEAN="clean" ;;
        --mise)
            USE_MISE="yes"
            # accept "--mise TASK" as well as "--mise=TASK", but only swallow the
            # next argument when it is a task name and not another option.
            if [ $# -gt 1 ] && [ -n "$2" ] && [ "${2#-}" = "$2" ]; then
                MISE_TASK="$2"
                shift
            fi
            ;;
        --mise=*)
            USE_MISE="yes"
            MISE_TASK="${1#--mise=}"
            [ -n "$MISE_TASK" ] || die "--mise= needs a task name"
            ;;
        --no-mise) USE_MISE="no" ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            echo "error: unknown option '$1'" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

# ------------------------------------------------------------- build backend

# A mise.toml pins the exact toolchain this build needs, so when the project has
# one, letting mise supply it is strictly more reliable than hoping the invoking
# shell already activated it. Ask rather than assume: switching build commands
# out from under someone is worse than one prompt.
if [ "$USE_MISE" = "yes" ] && [ ! -f mise.toml ]; then
    die "--mise given but there is no mise.toml here"
fi
[ -f mise.toml ] || USE_MISE="no"

if [ -z "$USE_MISE" ]; then
    if [ -t 0 ] && [ -t 1 ]; then
        echo "This project pins its toolchain in mise.toml."
        read -rp "Build through mise, or with the java on your PATH? [M/j] " reply || reply=""
        case "$reply" in
            [Jj]*) USE_MISE="no" ;;
            *) USE_MISE="yes" ;;
        esac
    else
        # Nothing to prompt at (CI, a pipe). Keep the old behaviour rather than
        # silently changing how an unattended build builds; --mise opts in.
        echo "note: mise.toml found — pass --mise to build through it" >&2
        USE_MISE="no"
    fi
fi

if [ "$USE_MISE" = "yes" ]; then
    command -v mise >/dev/null || die "mise.toml is present but mise is not installed; see https://mise.jdx.dev"

    # Tools listed in mise.toml are not installed until asked for, and `mise run`
    # would otherwise fail somewhere inside the task with a vaguer message.
    mise which java >/dev/null 2>&1 || die "mise has no java for this project; run 'mise install' here first"

    tasks="$(mise tasks --no-header 2>/dev/null | awk 'NF {print $1}')"
    [ -n "$tasks" ] || die "mise.toml defines no tasks to run"

    if [ -z "$MISE_TASK" ]; then
        if [ -t 0 ] && [ -t 1 ]; then
            echo
            echo "Available tasks:"
            mise tasks
            echo
            read -rp "Task to run [$DEFAULT_TASK]: " MISE_TASK || MISE_TASK=""
        fi
        MISE_TASK="${MISE_TASK:-$DEFAULT_TASK}"
    fi

    # Fail on a typo here, where we can list the alternatives, rather than after
    # the user has walked away from a build they think is running.
    if ! printf '%s\n' "$tasks" | grep -qxF -- "$MISE_TASK"; then
        echo "error: no mise task named '$MISE_TASK'" >&2
        echo "       available: $(printf '%s ' $tasks)" >&2
        exit 1
    fi

    # The task owns its own goals, so there is no clean phase for us to add.
    [ -n "$CLEAN" ] && echo "  note: --clean ignored; the '$MISE_TASK' task decides its own maven goals"
fi

# ---------------------------------------------------------------- preflight

if [ "$USE_MISE" != "yes" ]; then
    [ -x ./mvnw ] || die "./mvnw not found or not executable (run from a full checkout)"

    # native:compile needs a GraalVM JDK. The usual failure is running this from a
    # shell where mise was never activated, so `java` is the system JDK and the
    # build dies deep inside the plugin with a much less obvious message.
    JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
    [ -x "${JAVA_BIN:-}" ] || JAVA_BIN="$(command -v java || true)"
    [ -n "$JAVA_BIN" ] || die "no java found; run 'mise install' in this directory"

    if ! "$JAVA_BIN" -version 2>&1 | grep -qi graalvm; then
        echo "error: $JAVA_BIN is not a GraalVM JDK — native:compile cannot run." >&2
        echo "       This project pins one in mise.toml; activate it with:" >&2
        echo "         mise install && eval \"\$(mise activate bash)\"" >&2
        echo "       or re-run this script with --mise to let mise supply it." >&2
        exit 1
    fi
fi

# ---------------------------------------------------------------- build

if [ "$USE_MISE" = "yes" ]; then
    echo "==> Building native image via 'mise run $MISE_TASK'"
else
    echo "==> Building native image${CLEAN:+ (clean)}"
fi
echo "    this takes a few minutes and a couple of GB of RAM"
started=$(date +%s)

if [ "$USE_MISE" = "yes" ]; then
    mise run "$MISE_TASK"
else
    goals=()
    [ -n "$CLEAN" ] && goals+=("clean")
    goals+=("native:compile")
    ./mvnw -Pnative "${goals[@]}"
fi

# The build can exit 0 having produced nothing useful, so check rather than
# trust — otherwise we would happily install a stale binary from an earlier run
# over the top of itself and report success.
if [ ! -f "$BUILT" ]; then
    [ "$USE_MISE" = "yes" ] &&
        die "'$MISE_TASK' finished but $BUILT is missing — does that task build the native image?"
    die "build finished but $BUILT is missing"
fi
[ -s "$BUILT" ] || die "$BUILT is empty"
[ "$BUILT" -nt "pom.xml" ] || echo "  warning: $BUILT is older than pom.xml — is this a stale build?"

# ---------------------------------------------------------------- install

mkdir -p "$BIN_DIR"

# Install via a temp file in the same directory, then rename. mv is atomic, so
# an interrupted copy can never leave a half-written binary on PATH — and it
# replaces the file even while an older tambo is still running, which a plain
# cp cannot do ("Text file busy").
tmp="$(mktemp "$DEST.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
cp "$BUILT" "$tmp"
chmod 755 "$tmp"
mv -f "$tmp" "$DEST"
trap - EXIT

elapsed=$(($(date +%s) - started))
printf '\n✓ installed %s (%s) in %dm%02ds\n' \
    "$DEST" "$(du -h "$DEST" | cut -f1)" $((elapsed / 60)) $((elapsed % 60))

# ---------------------------------------------------------------- post-install

case ":$PATH:" in
    *":$BIN_DIR:"*)
        echo "  run it with: $BINARY"
        ;;
    *)
        echo
        echo "  note: $BIN_DIR is not on your PATH. Add it with:"
        echo "    echo 'export PATH=\"$BIN_DIR:\$PATH\"' >> ~/.bashrc"
        ;;
esac

# Earlier versions of this script installed a directory of files plus a wrapper
# script. The binary is self-contained (no sibling .so files, no AWT), so that
# layout is now dead weight — but it is not ours to delete unasked.
legacy="$PREFIX/lib/$BINARY"
if [ -d "$legacy" ]; then
    echo
    echo "  note: leftover install from the old layout can be removed:"
    echo "    rm -rf $legacy"
fi
