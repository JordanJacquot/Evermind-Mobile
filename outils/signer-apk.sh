#!/usr/bin/env bash
# Signe l'APK de publication (app-release-unsigned.apk) avec la clé de publication et sa lignée, puis le vérifie.
# Appelé par publier.sh. Les deux mots de passe sont tapés par Jordan, sans s'afficher, à chaque publication.
#
# Usage (Git Bash) : bash outils/signer-apk.sh <APK non signé> <APK signé>
#
# Comment c'est signé (docs/CLE-DE-PUBLICATION.md) :
#   - v1 et v2 : l'ancienne clé, celle que connaissent les téléphones sous Android 8 (ils ne lisent pas la v3) ;
#   - v3, Android 9 et plus récent : la clé de publication, avec la lignée qui prouve que l'ancienne l'autorise.
set -euo pipefail
. "$(dirname "$0")/signature-commun.sh"
trap oublier_mdp EXIT

ENTREE="$1"
SORTIE="$2"
for f in "$CLE_PUBLICATION" "$CLE_ANCIENNE" "$LIGNEE"; do
    [ -f "$f" ] || { echo "Clé de publication introuvable ($f). Lance d'abord : bash outils/creer-cle-publication.sh"; exit 1; }
done

[ -n "${EV_MDP_ANCIENNE:-}" ] || demander_mdp "Mot de passe de l'ancienne clé (débogage d'Android Studio)" EV_MDP_ANCIENNE
[ -n "${EV_MDP_PUBLICATION:-}" ] || demander_mdp "Mot de passe de la clé de publication" EV_MDP_PUBLICATION
apksigner sign \
    --ks "$CLE_ANCIENNE" --ks-key-alias "$ALIAS_ANCIENNE" --ks-pass env:EV_MDP_ANCIENNE \
    --next-signer --ks "$CLE_PUBLICATION" --ks-key-alias "$ALIAS_PUBLICATION" --ks-pass env:EV_MDP_PUBLICATION \
    --lineage "$LIGNEE" --rotation-min-sdk-version 28 --v4-signing-enabled false \
    --out "$SORTIE" "$ENTREE"
oublier_mdp

# Vérifications : signature valable depuis Android 8, v2 avec l'ancienne clé, v3 avec la nouvelle et sa lignée, pas débogable
verif=$(apksigner verify --print-certs --verbose --min-sdk-version 26 "$SORTIE")
echo "$verif" | grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' || { echo "Signature v2 absente."; exit 1; }
echo "$verif" | grep -q 'Verified using v3 scheme (APK Signature Scheme v3): true' || { echo "Signature v3 absente."; exit 1; }
# Android 8 (API 26 et 27) : l'ancienne clé, celle des versions déjà installées
android8=$(apksigner verify --print-certs --min-sdk-version 26 --max-sdk-version 27 "$SORTIE")
echo "$android8" | grep -q "^Signer #1 certificate SHA-256 digest: $EMPREINTE_ANCIENNE" || { echo "L'ancienne clé ne signe pas l'APK : les téléphones sous Android 8 refuseraient la mise à jour."; exit 1; }
# Android 9 et plus récent : la clé de publication, et l'ancienne dans sa lignée, avec le droit de garder les données
nouvelle=$(apksigner lineage --in "$LIGNEE" --print-certs | sed -nE 's/^Signer #2 in lineage certificate SHA-256 digest: ([0-9a-f]+).*/\1/p')
android9=$(apksigner verify --print-certs --verbose --min-sdk-version 28 "$SORTIE")
[ -n "$nouvelle" ] && echo "$android9" | grep -q "^Signer #1 certificate SHA-256 digest: $nouvelle" || { echo "La clé de publication ne signe pas l'APK."; exit 1; }
apksigner lineage --in "$SORTIE" --print-certs -v | grep -A 9 "^Signer #1 in lineage certificate SHA-256 digest: $EMPREINTE_ANCIENNE" | grep -q 'Has installed data capability: true' \
    || { echo "La lignée de l'APK ne permet pas la mise à jour depuis l'ancienne clé."; exit 1; }
if [ -f "$AAPT2" ] && "$AAPT2" dump badging "$SORTIE" 2>/dev/null | grep -q 'application-debuggable'; then
    echo "L'APK est « débogable » : ce n'est pas une version de publication."; exit 1
fi
echo "APK signé : ancienne clé (Android 8) et clé de publication avec sa lignée (Android 9 et plus récent)."
