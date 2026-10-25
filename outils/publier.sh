#!/usr/bin/env bash
# Publie une nouvelle version d'Evermind Mobile sur GitHub, au format que la mise à jour de l'app sait lire
# (maj/MiseAJour.kt) : l'étiquette « v<version>-beta », l'APK, et « SHA-256 de l'APK : <empreinte> » dans les notes.
# Les téléphones la trouvent au lancement suivant (au plus une fois par jour) et proposent « Mettre à jour ».
#
# Usage (Git Bash) : bash outils/publier.sh <version> "<ce qui change, une ligne par point, avec « - »>"
#   ex. : bash outils/publier.sh 0.2.3 "- Les rappels ont le logo d'Evermind."
# Le code de l'app doit être commité (le numéro de version est commité et poussé par ce script).
# L'APK est la version de publication (assembleRelease), signée par outils/signer-apk.sh avec la clé de publication et
# sa lignée : les deux mots de passe sont demandés (docs/CLE-DE-PUBLICATION.md).
set -eu
cd "$(dirname "$0")/.."
V="$1"
CHANGE="$2"
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
[ -z "$(git status --porcelain -- app)" ] || { echo "Des changements dans app/ ne sont pas commités : commite-les d'abord."; exit 1; }
[ -f "${EVERMIND_SIGNATURE:-$HOME/.evermind-signature}/lignee.bin" ] || { echo "Pas de clé de publication : lance d'abord bash outils/creer-cle-publication.sh"; exit 1; }
git rev-parse -q --verify "refs/tags/v$V-beta" > /dev/null && { echo "La version $V existe déjà."; exit 1; }

# 1. Le numéro de version (versionCode + 1, versionName)
code=$(sed -nE 's/^ *versionCode = ([0-9]+).*/\1/p' app/build.gradle.kts)
code=$((code + 1))
sed -i -E "s/^( *versionCode = )[0-9]+/\1$code/; s/^( *versionName = )\"[^\"]*\"/\1\"$V\"/" app/build.gradle.kts
echo "Version $V (code $code)"

# 2. L'APK (version de publication, signée avec la clé de publication) et son empreinte
./gradlew assembleRelease -q
DOSSIER=$(mktemp -d)
APK="$DOSSIER/Evermind-Mobile-$V-beta.apk"
bash outils/signer-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk "$APK" || {
    echo "Signature ratée : rien n'est publié. Le numéro de version reste changé dans app/build.gradle.kts (git checkout pour l'enlever)."
    exit 1
}
SHA=$(sha256sum < "$APK" | cut -d' ' -f1)

# 3. Les notes (la ligne « SHA-256 de l'APK » est lue par l'app : ne pas la changer)
cat > "$DOSSIER/notes.md" <<FIN
**Installer :** télécharger \`Evermind-Mobile-$V-beta.apk\` sur le téléphone (Android 8 ou plus récent), l'ouvrir et autoriser l'installation. Si Evermind est déjà installé (0.2.2 ou plus récente), il propose lui-même la mise à jour : bandeau « Une mise à jour est disponible », puis « Mettre à jour ». Les rendez-vous et l'assistant sont gardés.

**Nouveau**
$CHANGE

Version de test, signée avec la clé de publication d'Evermind.

SHA-256 de l'APK : $SHA
FIN

# 4. Le numéro de version commité, puis la version GitHub
git add app/build.gradle.kts
git commit -q -m "Version $V (bêta)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push -q
gh release create "v$V-beta" "$APK" --prerelease --target main --title "Evermind Mobile $V (bêta)" --notes-file "$DOSSIER/notes.md"
echo "Publié : $V (SHA-256 $SHA)"
rm -rf "$DOSSIER"
