# Ce que partagent creer-cle-publication.sh et signer-apk.sh (à inclure avec « . », pas à lancer).
# Les clés sont rangées hors du dépôt, dans un dossier à sauvegarder hors du PC (docs/CLE-DE-PUBLICATION.md).

DOSSIER_CLES="${EVERMIND_SIGNATURE:-$HOME/.evermind-signature}"
# La clé de publication (nouvelle), la copie de l'ancienne clé (celle de débogage d'Android Studio, qui a signé les
# versions jusqu'à 0.2.4), et la lignée qui dit à Android que la nouvelle remplace l'ancienne
CLE_PUBLICATION="$DOSSIER_CLES/evermind-publication.jks"
CLE_ANCIENNE="$DOSSIER_CLES/cle-debogage-android.keystore"
LIGNEE="$DOSSIER_CLES/lignee.bin"
ALIAS_PUBLICATION="evermind"
ALIAS_ANCIENNE="androiddebugkey"
# Le certificat de l'ancienne clé, tel qu'il est dans les APK publiés (empreinte publique, pas un secret) : on refuse
# de faire une lignée depuis une autre clé, les téléphones refuseraient la mise à jour
EMPREINTE_ANCIENNE="${EVERMIND_EMPREINTE_ANCIENNE:-5d16494a34b391076b4ee12b8cd318565b63865a88d23a84432010cd94482689}"

export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
JAVA="$JAVA_HOME/bin/java"
KEYTOOL="$JAVA_HOME/bin/keytool"

# apksigner et aapt2 : le dossier build-tools le plus récent du SDK Android
sdk_android() {
    local d
    d=$(sed -nE 's/^sdk\.dir=(.*)$/\1/p' "$(dirname "${BASH_SOURCE[0]}")/../local.properties" 2>/dev/null | sed -e 's/\\:/:/g' -e 's/\\\\/\//g' -e 's/\\/\//g')
    [ -n "$d" ] || d="${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk"
    echo "$d"
}
BUILD_TOOLS=$(ls -d "$(sdk_android)"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
[ -f "$BUILD_TOOLS/lib/apksigner.jar" ] || { echo "apksigner introuvable (SDK Android, build-tools)."; exit 1; }
apksigner() { "$JAVA" -jar "$BUILD_TOOLS/lib/apksigner.jar" "$@" 2> >(grep -v '^WARNING' >&2); }
AAPT2="$BUILD_TOOLS/aapt2.exe"
[ -f "$AAPT2" ] || AAPT2="$BUILD_TOOLS/aapt2"

# Un mot de passe tapé sans qu'il s'affiche ; gardé seulement dans une variable de ce script, jamais dans un fichier
demander_mdp() {
    local message="$1" variable="$2" mdp
    printf '%s : ' "$message" >&2
    IFS= read -rs mdp
    echo >&2
    printf -v "$variable" '%s' "$mdp"
    export "$variable"
}
oublier_mdp() { unset EV_MDP_ANCIENNE EV_MDP_PUBLICATION EV_MDP_CONFIRMATION; }
