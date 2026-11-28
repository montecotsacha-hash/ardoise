#!/usr/bin/env bash
# Prépare le site à déployer, TOUJOURS depuis le dépôt qui contient ce script.
#
# Pourquoi : un déploiement a déjà servi une page en 1.6.9 parce que l'outil de
# déploiement lisait une copie de travail (worktree) restée à un vieux commit.
# Ce script reconstruit `deploy/assets` à partir de `src/version.ts`, refuse un
# bundle qui ne porte pas la version courante, puis aligne la copie que lit
# l'outil de déploiement sur ce build.
#
# Usage :
#   bash scripts/deploy-site.sh                          # build + garde
#   bash scripts/deploy-site.sh --mirror-to <dossier>    # + copie du deploy/
#   bash scripts/deploy-site.sh --no-mirror              # sans copie
#
# Sans option, la copie est faite automatiquement quand le script est lancé
# depuis un workspace Freebuff (`…/.freebuff/worktrees/…`), seul cas où l'outil
# de déploiement et le dépôt divergent.
set -euo pipefail

origin=$PWD
repo=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo"

mirror=""
auto_mirror=1
while [ $# -gt 0 ]; do
  case "$1" in
    --mirror-to)
      mirror=${2:-}
      [ -n "$mirror" ] || { echo "--mirror-to attend un dossier" >&2; exit 2; }
      shift 2
      ;;
    --no-mirror)
      auto_mirror=0
      shift
      ;;
    -h | --help)
      sed -n '2,20p' "$0"
      exit 0
      ;;
    *)
      echo "option inconnue : $1" >&2
      exit 2
      ;;
  esac
done

read_field() { sed -n "s/^export const $1 = \"\(.*\)\";/\1/p" src/version.ts; }
version=$(read_field VERSION)
codename=$(read_field VERSION_CODENAME)
[ -n "$version" ] && [ -n "$codename" ] || {
  echo "version illisible dans src/version.ts" >&2
  exit 1
}
label="$version « $codename »"
echo "[site] version du dépôt : $label (commit $(git rev-parse --short HEAD))"

# 1. Build du site, avec le gestionnaire présent sur la machine.
bun_bin=$(command -v bun || true)
if [ -z "$bun_bin" ] && [ -n "${FREEBUFF_BUN:-}" ] && [ -x "${FREEBUFF_BUN}" ]; then
  bun_bin=$FREEBUFF_BUN
fi
if [ -n "$bun_bin" ]; then
  "$bun_bin" run build
else
  npm run build
fi

# 2. `deploy/assets` = exactement ce build, sans l'APK (il est servi depuis R2).
rm -rf deploy/assets
cp -r dist deploy/assets
rm -f deploy/assets/Ardoise.apk

bundle=$(grep -o 'index-[A-Za-z0-9_-]*\.js' deploy/assets/index.html | head -1 || true)
if [ -z "$bundle" ]; then
  echo "::error::deploy/assets/index.html ne référence aucun bundle JS" >&2
  exit 1
fi
if [ ! -f "deploy/assets/assets/$bundle" ]; then
  echo "::error::$bundle référencé mais absent de deploy/assets/assets" >&2
  exit 1
fi

# 3. Garde : le bundle doit vraiment porter la version du dépôt. Un build
#    périmé est indiscernable à l'œil sur la page, mais pas dans le paquet.
if ! grep -qF "\"$version\"" "deploy/assets/assets/$bundle" ||
  ! grep -qF "\"$codename\"" "deploy/assets/assets/$bundle"; then
  echo "::error::$bundle ne contient pas « $label » : build périmé, rien n'est déployable" >&2
  exit 1
fi
echo "[site] $bundle porte bien « $label »"

# 4. La copie lue par l'outil de déploiement doit être ce build, pas un ancien.
if [ -z "$mirror" ] && [ "$auto_mirror" -eq 1 ]; then
  case "$origin" in
    */.freebuff/worktrees/*) mirror=$origin ;;
  esac
fi
if [ -n "$mirror" ]; then
  target=$(cd "$mirror" 2>/dev/null && pwd) || {
    echo "::error::workspace introuvable : $mirror" >&2
    exit 1
  }
  if [ "$target" = "$repo" ]; then
    echo "[site] workspace = dépôt, aucune copie nécessaire"
  else
    rm -rf "$target/deploy/assets"
    mkdir -p "$target/deploy"
    cp -r deploy/. "$target/deploy/"
    rm -f "$target/deploy/assets/Ardoise.apk"
    if [ "$(grep -o 'index-[A-Za-z0-9_-]*\.js' "$target/deploy/assets/index.html" | head -1)" != "$bundle" ]; then
      echo "::error::la copie de $target ne correspond pas au build : déploiement annulé" >&2
      exit 1
    fi
    echo "[site] deploy/ synchronisé dans $target"
  fi
fi

echo "[site] prêt : déployez deploy/site.manifest.json puis lancez scripts/verify-site.sh"
