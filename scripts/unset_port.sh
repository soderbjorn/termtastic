# Must be sourced — `unset` in a child shell cannot modify the parent's env.
# Usage:  source scripts/unset_port.sh   (or: . scripts/unset_port.sh)
if ! (return 0 2>/dev/null); then
    echo "error: this script must be sourced, not executed" >&2
    echo "       source scripts/unset_port.sh" >&2
    exit 1
fi

unset TERMTASTIC_PORT
