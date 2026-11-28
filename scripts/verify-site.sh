#!/usr/bin/env bash
# Vérifie APRÈS chaque déploiement que la page publique annonce bien la version
# du dépôt — la panne qui a motivé ce script : la 1.6.12 était publiée, la page
# servait encore le bundle de la 1.6.9.
#
# Trois contrôles, tous sur le site réel :
#   1. la page référence un bundle JS existant ;
#   2. ce bundle contient « <version> » et « <nom de version> » ;
#   3. /version.json annonce la même version et le même versionCode que Gradle.
#
# Usage : bash scripts/verify-site.sh [url]   (défaut : https://ardoise.freebuff.page)
# Réglages : VERIFY_ATTEMPTS (5) et VERIFY_DELAY secondes (10).
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo"

site=${1:-https://ardoise.freebuff.page}
attempts=${VERIFY_ATTEMPTS:-5}
delay=${VERIFY_DELAY:-10}

read_field() { sed -n "s/^export const $1 = \"\(.*\)\";/\1/p" src/version.ts; }
version=$(read_field VERSION)
codename=$(read_field VERSION_CODENAME)
code=$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' android/app/build.gradle.kts | head -1)
label="$version « $codename »"
[ -n "$version" ] && [ -n "$codename" ] && [ -n "$code" ] || {
  echo "version illisible dans src/version.ts / android/app/build.gradle.kts" >&2
  exit 1
}

echo "[verify] $site doit annoncer $label (versionCode $code)"

last=""
check() {
  local html bundle js manifest
  html=$(curl -fsS --max-time 10 "$site/") || { last="page d'accueil injoignable"; return 1; }
  bundle=$(printf '%s' "$html" | grep -o 'index-[A-Za-z0-9_-]*\.js' | head -1)
  if [ -z "$bundle" ]; then
    last="la page ne référence aucun bundle JS"
    return 1
  fi
  js=$(curl -fsS --max-time 20 "$site/assets/$bundle") || {
    last="$bundle injoignable ($site/assets/$bundle)"
    return 1
  }
  case "$js" in
    *"\"$version\""*"\"$codename\""*) ;;
    *)
      last="la page sert $bundle, qui n'annonce pas $label"
      return 1
      ;;
  esac
  manifest=$(curl -fsS --max-time 10 "$site/version.json") || {
    last="/version.json injoignable"
    return 1
  }
  case "$manifest" in
    *"\"versionCode\":$code"*) ;;
    *)
      last="/version.json n'annonce pas versionCode $code"
      return 1
      ;;
  esac
  case "$manifest" in
    *"\"versionName\":\"$version\""*) ;;
    *)
      last="/version.json n'annonce pas versionName $version"
      return 1
      ;;
  esac
  echo "[verify] OK : $site sert $bundle avec $label, /version.json annonce $code"
  return 0
}

for attempt in $(seq 1 "$attempts"); do
  if check; then
    exit 0
  fi
  echo "[verify] tentative $attempt/$attempts : $last"
  [ "$attempt" -lt "$attempts" ] && sleep "$delay"
done

echo "::error::le déploiement n'est pas visible : $last" >&2
echo "Attendu : $label, versionCode $code. Un cache de bord ou un deploy/ périmé en est la cause habituelle." >&2
exit 1
