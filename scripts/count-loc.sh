#!/usr/bin/env bash
#
# count-loc.sh
#
# Counts lines of code across the Lunamux codebase, grouped by the role each
# module plays, and prints a per-group breakdown followed by a grand TOTAL.
#
# Two counts are reported per group:
#   Total  - every line in the matched source files (raw `wc -l` equivalent).
#   Code   - lines that still contain source after stripping comments; this
#            drops full-line and block comments and any line left blank once its
#            comment is removed (i.e. blank lines and comment-only lines).
#
# Groups (see settings.gradle.kts for the module layout):
#   Server                        - server/                (JVM backend)
#   Shared between all clients    - client/                (KMP module shared by
#                                                           every client target)
#   Shared between server+clients - clientServer/          (protocol/model code
#                                                           used on both ends)
#   Electron                      - electron/*.js + electron-main/  (desktop shell
#                                                           + Kotlin/JS main process)
#   Web                           - web/                   (Kotlin/JS renderer that
#                                                           powers the browser AND
#                                                           the Electron window)
#   Android                       - androidApp/            (Android app)
#   iOS                           - iosApp/iosApp/          (SwiftUI app)
#   Darkness Toolkit              - ../../darkness-toolkit  (sibling toolkit-*
#                                                           modules consumed via
#                                                           the Gradle composite
#                                                           build; only counted
#                                                           when the checkout is
#                                                           present on disk)
#
# Vendored / third-party modules (terminal-emulator, terminal-view) and the
# examples/ directory are intentionally excluded, as are generated/dependency
# trees (node_modules, build, dist). Counted code file types: .kt .java .sq
# (Kotlin modules), .js (Electron), .swift (iOS).
#
# Comment stripping recognises C-style `//` line comments and `/* ... */` block
# comments (the syntax used by Kotlin, Java, JavaScript and Swift). It is a
# lexical approximation: it does not parse string literals, so a `//` or `/*`
# inside a string is treated as a comment. In practice this only affects the
# rare line whose *only* content sits after such a token, so the Code figure is
# a close estimate rather than an exact SLOC.
#
# Usage:
#   scripts/count-loc.sh
#
# Override the Darkness Toolkit location with:
#   DARKNESS_TOOLKIT_PATH=/path/to/darkness-toolkit/checkout scripts/count-loc.sh

set -euo pipefail

# Resolve the repo root from this script's location so it works from any CWD.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# awk program that, for the files handed to it, prints two space-separated
# integers: "<total-lines> <code-lines>". Block-comment state (`inblock`) is
# reset at the first record of every file (FNR==1) so an odd file cannot bleed
# state into the next one.
read -r -d '' AWK_SLOC <<'AWK' || true
# Returns 1 if the line holds any code once comments are stripped, else 0.
function has_code(line,   out, i, n, c, c2) {
  out = ""
  n = length(line)
  i = 1
  while (i <= n) {
    c  = substr(line, i, 1)
    c2 = substr(line, i, 2)
    if (inblock) {
      if (c2 == "*/") { inblock = 0; i += 2 } else { i += 1 }
      continue
    }
    if (c2 == "/*") { inblock = 1; i += 2; continue }
    if (c2 == "//") { break }          # line comment: ignore the remainder
    out = out c
    i += 1
  }
  gsub(/[ \t\r]/, "", out)
  return (length(out) > 0)
}
FNR == 1 { inblock = 0 }
{ total += 1; if (has_code($0)) code += 1 }
END { print total + 0, code + 0 }
AWK

# tally: reads a NUL-delimited file list on stdin and prints "<total> <code>".
# xargs may split a huge list across several awk invocations, so the trailing
# awk sums their partial totals back together.
tally() {
  xargs -0 awk "$AWK_SLOC" 2>/dev/null \
    | awk '{ t += $1; c += $2 } END { print t + 0, c + 0 }'
}

# count_loc <root-dir...> -- <ext...>  ->  "<total> <code>"
#
# Counts lines across all files under the given root directories whose extension
# matches one of the listed extensions. Directories named node_modules, build or
# dist are pruned so generated/dependency code is never counted. Missing
# directories contribute 0.
#
# Args:
#   Everything before the literal `--` token is a root directory; everything
#   after it is a bare file extension (no leading dot).
count_loc() {
  local dirs=() exts=() seen_sep=0 arg
  for arg in "$@"; do
    if [ "$arg" = "--" ]; then seen_sep=1; continue; fi
    if [ "$seen_sep" -eq 0 ]; then dirs+=("$arg"); else exts+=("$arg"); fi
  done

  local name_pred=() first=1 e
  for e in "${exts[@]}"; do
    if [ "$first" -eq 1 ]; then
      name_pred+=(-name "*.$e"); first=0
    else
      name_pred+=(-o -name "*.$e")
    fi
  done

  find "${dirs[@]}" \
      -type d \( -name node_modules -o -name build -o -name dist \) -prune -o \
      -type f \( "${name_pred[@]}" \) -print0 2>/dev/null \
    | tally
}

# format_int <n>  ->  n with thousands separators (e.g. 37161 -> 37,161)
format_int() {
  printf "%s" "$1" | awk '{
    n = $0; s = ""
    while (length(n) > 3) {
      s = "," substr(n, length(n) - 2) s
      n = substr(n, 1, length(n) - 3)
    }
    print n s
  }'
}

# --- Per-group counts (each captures "<total> <code>") ----------------------

read -r server_t server_c            < <(count_loc server/src -- kt java sq)
read -r sclients_t sclients_c        < <(count_loc client/src -- kt java sq)
read -r ssc_t ssc_c                  < <(count_loc clientServer/src -- kt java sq)

# Electron = the desktop shell's own root JS (electron/main.js, preload.js — NOT
# bundled node_modules) plus the Kotlin/JS main process.
read -r eshell_t eshell_c < <(find electron -maxdepth 1 -type f -name '*.js' -print0 2>/dev/null | tally)
read -r emain_t emain_c              < <(count_loc electron-main/src -- kt js)
electron_t=$(( eshell_t + emain_t ))
electron_c=$(( eshell_c + emain_c ))

read -r web_t web_c                  < <(count_loc web/src -- kt)
read -r android_t android_c          < <(count_loc androidApp/src -- kt java)
read -r ios_t ios_c                  < <(count_loc iosApp/iosApp -- swift)

# Darkness Toolkit lives in a sibling checkout and is pulled in via a Gradle
# composite build (see settings.gradle.kts). Auto-detect it the same way Gradle
# does — honouring a DARKNESS_TOOLKIT_PATH override — and count its toolkit-*
# module sources. When no checkout is found, its counts stay 0.
toolkit_t=0; toolkit_c=0; toolkit_path=""
for cand in "${DARKNESS_TOOLKIT_PATH:-}" ../../darkness-toolkit/develop ../../darkness-toolkit/main; do
  [ -n "$cand" ] || continue
  if [ -f "$cand/settings.gradle.kts" ]; then toolkit_path="$cand"; break; fi
done
if [ -n "$toolkit_path" ]; then
  read -r toolkit_t toolkit_c < <(count_loc \
    "$toolkit_path/toolkit-core/src" \
    "$toolkit_path/toolkit-store/src" \
    "$toolkit_path/toolkit-web/src" \
    "$toolkit_path/toolkit-compose/src" \
    -- kt java sq)
fi

total_t=$(( server_t + sclients_t + ssc_t + electron_t + web_t + android_t + ios_t + toolkit_t ))
total_c=$(( server_c + sclients_c + ssc_c + electron_c + web_c + android_c + ios_c + toolkit_c ))

# --- Output -----------------------------------------------------------------

label_w=32
row() { printf "  %-${label_w}s %12s %12s\n" "$1" "$2" "$3"; }
rule() { row "------------------------------" "------------" "------------"; }

printf "\n"
row "Lunamux — Lines of Code" "Total" "Code"
rule
row "Server"                    "$(format_int "$server_t")"   "$(format_int "$server_c")"
row "Shared (all clients)"      "$(format_int "$sclients_t")" "$(format_int "$sclients_c")"
row "Shared (server + clients)" "$(format_int "$ssc_t")"      "$(format_int "$ssc_c")"
row "Electron"                  "$(format_int "$electron_t")" "$(format_int "$electron_c")"
row "Web"                       "$(format_int "$web_t")"      "$(format_int "$web_c")"
row "Android"                   "$(format_int "$android_t")"  "$(format_int "$android_c")"
row "iOS"                       "$(format_int "$ios_t")"      "$(format_int "$ios_c")"
if [ "$toolkit_t" -gt 0 ]; then
  row "Darkness Toolkit"        "$(format_int "$toolkit_t")"  "$(format_int "$toolkit_c")"
else
  row "Darkness Toolkit (no checkout)" "0" "0"
fi
rule
row "TOTAL LOC"                 "$(format_int "$total_t")"    "$(format_int "$total_c")"
printf "\n"
printf "  Total = all lines; Code = excludes comments and blank lines.\n\n"
