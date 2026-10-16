#!/usr/bin/env bash
# Le banc de mesure de l'assistant (docs/PLAN-1.0.md) : les 18 lots écrits à la main (règles seules), puis chaque série
# de outils/phrases-generees/series.txt avec les règles seules, le modèle seul, et comme dans l'app (règles + modèle).
# Le tableau est affiché, et ajouté à docs/MESURES.md ; le détail des échecs va dans app/build/mesures/<tour>/.
#
# Usage (Git Bash, depuis n'importe où) : bash outils/mesurer.sh [modèle.gguf] [nom du tour]
#   sans modèle : les règles seules
#   MESURER_FINALE=1 : aussi les séries finales (leur score seulement, jamais le détail des échecs)
#   MESURER_LOTS=0 : sans les 18 lots (plus rapide) ; MESURER_SERIES=0 : seulement les lots ; MESURER_NOTER=0 : un essai, rien d'ajouté à docs/MESURES.md
#   EVERMIND_IA : le dossier de llama-server.exe (celui d'Evermind PC)
set -u
cd "$(dirname "$0")/.."
RACINE="$(pwd -W)"
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
MODELE="${1:-}"
TOUR="${2:-$(date +%Y-%m-%d)}"
DETAILS="app/build/mesures/$TOUR"
mkdir -p "$DETAILS"

IA=""
for d in "${EVERMIND_IA:-}" "${LOCALAPPDATA:-}/Evermind/voix/ia" "/d/Mindset/Donnees/Evermind/voix/ia"; do
    if [ -n "$d" ] && [ -f "$d/llama-server.exe" ]; then IA="$d"; break; fi
done
if [ -n "$MODELE" ] && [ -z "$IA" ]; then echo "llama-server.exe introuvable : donne son dossier dans EVERMIND_IA"; exit 1; fi

# « 12 / 15 » dans la dernière ligne d'un résultat
score() { tail -1 "$1" 2>/dev/null | sed -nE 's/.*[^0-9]([0-9]+) \/ ([0-9]+).*/\1 \2/p'; }
pourcent() { [ "$2" -gt 0 ] && echo "$(( 100 * $1 / $2 )) %" || echo "-"; }
lancer() { ./gradlew testDebugUnitTest --tests "$1" --rerun --console=plain -q > /dev/null 2>&1; }

lignes=()

# --- Les 18 lots écrits à la main : les règles seules, et comme dans l'app quand un modèle est donné
if [ "${MESURER_LOTS:-1}" != "0" ]; then
    res=()
    for mode in regles app; do
        if [ "$mode" = "app" ] && [ -z "$MODELE" ]; then res+=("—"); continue; fi
        n=0; t=0
        for c in tour8 tour7 tour6 mesure tour5 tour4 tour3 couverture couverture-verif complexe complexe-verif pc examen contre-examen bases bases-verif quotidien quotidien-verif; do
            rm -f app/build/catalogue-assistant.txt
            (
                export EVERMIND_CATALOGUE=$c
                if [ "$mode" = "regles" ]; then
                    export EVERMIND_SANS_IA=1 EVERMIND_SANS_CONTROLE=1
                else
                    export EVERMIND_LLAMA_SERVEUR="$IA/llama-server.exe" EVERMIND_LLAMA_MODELE="$MODELE" EVERMIND_CONSIGNES=courtes
                    export LLAMA_ARG_CHAT_TEMPLATE_KWARGS='{"enable_thinking":false}'
                fi
                lancer '*CatalogueAssistantTest*'
            )
            cp app/build/catalogue-assistant.txt "$DETAILS/lot-$c-$mode.txt" 2>/dev/null || echo "PLANTÉ : 0 / 1" > "$DETAILS/lot-$c-$mode.txt"
            read -r a b <<< "$(score "$DETAILS/lot-$c-$mode.txt")"
            n=$((n + ${a:-0})); t=$((t + ${b:-1}))
        done
        res+=("$n / $t ($(pourcent $n $t))")
    done
    echo "Lots écrits à la main : règles ${res[0]} · app ${res[1]}"
    lignes+=("| $TOUR | lots écrits à la main | base | — | ${res[0]} | — | ${res[1]} |")
fi

# --- Les séries
while [ "${MESURER_SERIES:-1}" != "0" ] && read -r nom type role moment; do
    [ -z "$nom" ] || [ "${nom:0:1}" = "#" ] && continue
    [ "$role" = "finale" ] && [ "${MESURER_FINALE:-0}" != "1" ] && continue
    res=()
    for mode in regles seule app; do
        if [ "$mode" != "regles" ] && [ -z "$MODELE" ]; then res+=("—"); continue; fi
        rm -f app/build/generees.txt
        (
            export EVERMIND_GENERE="$RACINE/outils/phrases-generees/$nom.jsonl" EVERMIND_ECARTEES="$RACINE/outils/phrases-generees/$nom-ecartees.txt" EVERMIND_MAINTENANT="$moment"
            [ "$role" = "finale" ] && export EVERMIND_SCORE_SEUL=1
            if [ "$mode" = "regles" ]; then
                export EVERMIND_SANS_IA=1 EVERMIND_SANS_CONTROLE=1
            else
                export EVERMIND_LLAMA_SERVEUR="$IA/llama-server.exe" EVERMIND_LLAMA_MODELE="$MODELE" EVERMIND_CONSIGNES=courtes
                export LLAMA_ARG_CHAT_TEMPLATE_KWARGS='{"enable_thinking":false}'
                [ "$mode" = "seule" ] && export EVERMIND_IA_SEULE=1
            fi
            lancer '*CatalogueAssistantTest.generees*'
        )
        read -r a b <<< "$(score app/build/generees.txt)"
        if [ -z "${a:-}" ]; then res+=("planté"); continue; fi
        [ "$role" != "finale" ] && cp app/build/generees.txt "$DETAILS/$nom-$mode.txt"
        res+=("$a / $b ($(pourcent "$a" "$b"))")
    done
    rm -f app/build/generees.txt
    echo "$nom ($type, $role) : règles ${res[0]} · modèle seul ${res[1]} · app ${res[2]}"
    lignes+=("| $TOUR | $nom | $type | $role | ${res[0]} | ${res[1]} | ${res[2]} |")
done < outils/phrases-generees/series.txt

# --- L'historique
if [ ! -f docs/MESURES.md ]; then
    cat > docs/MESURES.md <<'FIN'
# Les mesures de l'assistant

Une ligne par série, à chaque tour (`bash outils/mesurer.sh [modèle.gguf] [nom du tour]`, voir docs/PLAN-1.0.md).
« app » : comme dans l'application, les règles d'abord, puis le modèle.

| Tour | Série | Type | Rôle | Règles seules | Modèle seul | App |
|---|---|---|---|---|---|---|
FIN
fi
if [ "${MESURER_NOTER:-1}" = "1" ]; then
    printf '%s\n' "${lignes[@]}" >> docs/MESURES.md
    echo "Ajouté à docs/MESURES.md ; détails dans $DETAILS"
else
    echo "(essai : rien d'ajouté à docs/MESURES.md) ; détails dans $DETAILS"
fi
