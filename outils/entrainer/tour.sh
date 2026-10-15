#!/usr/bin/env bash
# Un tour d'entraînement complet (docs/PLAN-1.0.md), à lancer le soir : le PC est très occupé pendant 3 à 4 heures.
#   1. la fabrique : des tâches toutes neuves (FabriqueEntrainementTest), jamais celles des tours d'avant
#   2. les phrases de l'utilisateur, écrites par le Qwen3 4B d'Evermind PC, avec son juge (phrases.py)
#   3. l'entraînement, toujours depuis le Qwen3 0.6B d'origine (entrainer.py), puis la conversion en GGUF
#   4. la mesure (outils/mesurer.sh, sans les séries finales), ajoutée à docs/MESURES.md
# Usage (Git Bash) : bash outils/entrainer/tour.sh <nom du tour> [graine] [nombre de tâches] [époques]
set -u
cd "$(dirname "$0")/../.."
NOM="$1"; GRAINE="${2:-23}"; N="${3:-10000}"; EPOQUES="${4:-2}"
E="$LOCALAPPDATA/Evermind-entrainement"
D="$E/donnees/$NOM"
mkdir -p "$D"
export PYTHONIOENCODING=utf-8 JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
PY="$E/.venv/Scripts/python.exe"
C=$(find app/.cxx -name convert_hf_to_gguf.py | head -1)
echo "[$(date +%H:%M)] tour $NOM : $N tâches (graine $GRAINE), $EPOQUES époques"

if [ ! -s "$D/taches.jsonl" ]; then
    EVERMIND_FABRIQUE="$(cygpath -w "$D/taches.jsonl")" EVERMIND_FABRIQUE_N=$N EVERMIND_FABRIQUE_GRAINE=$GRAINE \
        ./gradlew testDebugUnitTest --rerun --tests "*FabriqueEntrainementTest*" -q -i 2>&1 | grep -E "Fabrique :|^e:" | cut -c1-140
fi
[ -s "$D/taches.jsonl" ] || { echo "la fabrique n'a rien écrit"; exit 1; }

echo "[$(date +%H:%M)] phrases"
[ -s "$D/exemples.jsonl" ] || python outils/entrainer/phrases.py "$(cygpath -w "$D/taches.jsonl")" "$(cygpath -w "$D/exemples.jsonl")" 4 8 2>&1 | grep -E "^[0-9]+000/|gardées|introuvable|rror"
[ -s "$D/exemples.jsonl" ] || { echo "aucune phrase écrite"; exit 1; }

echo "[$(date +%H:%M)] entraînement"
if [ ! -f "$E/modeles/evermind-0.6b-$NOM/config.json" ]; then
    "$PY" outils/entrainer/entrainer.py "$(cygpath -w "$D/exemples.jsonl")" "$(cygpath -w "$E/modeles/Qwen3-0.6B")" "$(cygpath -w "$E/modeles/evermind-0.6b-$NOM")" "$EPOQUES" 64 2>&1 \
        | grep -vE "it/s\]|Warning|warn|^pas " | tail -12
fi
GGUF="$E/modeles/evermind-0.6b-$NOM-q8_0.gguf"
[ -f "$GGUF" ] || "$PY" "$C" "$(cygpath -w "$E/modeles/evermind-0.6b-$NOM")" --outtype q8_0 --outfile "$(cygpath -w "$GGUF")" 2>&1 | tail -1
[ -f "$GGUF" ] || { echo "pas de modèle converti"; exit 1; }

echo "[$(date +%H:%M)] mesure"
bash outils/mesurer.sh "$GGUF" "$NOM" 2>&1 | tail -8
echo "[$(date +%H:%M)] tour $NOM fini"
