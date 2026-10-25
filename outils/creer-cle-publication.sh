#!/usr/bin/env bash
# Une seule fois, par Jordan : crée la clé de publication d'Evermind Mobile, et la « lignée » qui dit à Android que
# cette clé remplace l'ancienne (la clé de débogage d'Android Studio, qui a signé les versions jusqu'à 0.2.4).
# Les téléphones acceptent alors la mise à jour sans désinstaller : l'agenda reste. Voir docs/CLE-DE-PUBLICATION.md.
#
# Usage (Git Bash) : bash outils/creer-cle-publication.sh
# Les mots de passe sont tapés par toi, sans s'afficher ; ils ne sont écrits dans aucun fichier.
set -euo pipefail
. "$(dirname "$0")/signature-commun.sh"
trap oublier_mdp EXIT

ANCIENNE_SOURCE="${EVERMIND_CLE_DEBOGAGE:-$HOME/.android/debug.keystore}"
[ ! -e "$CLE_PUBLICATION" ] && [ ! -e "$LIGNEE" ] || { echo "Une clé de publication existe déjà dans $DOSSIER_CLES : rien n'est touché."; exit 1; }
[ -f "$ANCIENNE_SOURCE" ] || { echo "Ancienne clé introuvable : $ANCIENNE_SOURCE"; exit 1; }

echo "1. L'ancienne clé (débogage d'Android Studio) : $ANCIENNE_SOURCE"
echo "   Son mot de passe : si tu ne l'as jamais changé, c'est celui par défaut des clés de débogage d'Android"
echo "   (page « Sign your app » de developer.android.com)."
demander_mdp "   Mot de passe de l'ancienne clé" EV_MDP_ANCIENNE
# (l'empreinte du certificat lui-même : le texte de « keytool -list » change avec la langue de Windows)
cert=$("$KEYTOOL" -exportcert -rfc -keystore "$ANCIENNE_SOURCE" -alias "$ALIAS_ANCIENNE" -storepass:env EV_MDP_ANCIENNE 2>/dev/null) || cert=""
[ -n "$cert" ] || { echo "   Impossible de lire l'ancienne clé (mot de passe ?)."; exit 1; }
empreinte=$(printf '%s\n' "$cert" | sed '/CERTIFICATE/d' | tr -d '\r\n' | base64 -d | sha256sum | cut -d' ' -f1)
if [ "$empreinte" != "$EMPREINTE_ANCIENNE" ]; then
    echo "   Ce n'est pas la clé qui a signé les versions publiées (empreinte $empreinte)."
    echo "   Rien n'est créé : une lignée depuis cette clé ne servirait à rien aux téléphones."
    exit 1
fi
echo "   C'est bien la clé des versions publiées."

echo "2. La nouvelle clé de publication : $CLE_PUBLICATION"
echo "   Choisis un mot de passe long, et note-le en lieu sûr (gestionnaire de mots de passe) : sans lui, plus de mise à jour."
while true; do
    demander_mdp "   Nouveau mot de passe (12 caractères ou plus)" EV_MDP_PUBLICATION
    demander_mdp "   Encore une fois" EV_MDP_CONFIRMATION
    if [ "${#EV_MDP_PUBLICATION}" -lt 12 ]; then echo "   Trop court."; continue; fi
    if [ "$EV_MDP_PUBLICATION" != "$EV_MDP_CONFIRMATION" ]; then echo "   Les deux ne sont pas pareils."; continue; fi
    break
done
mkdir -p "$DOSSIER_CLES"
chmod 700 "$DOSSIER_CLES" 2>/dev/null || true
# (une copie de l'ancienne clé : elle signe encore chaque version pour les téléphones sous Android 8)
cp "$ANCIENNE_SOURCE" "$CLE_ANCIENNE"
"$KEYTOOL" -genkeypair -keystore "$CLE_PUBLICATION" -storetype PKCS12 -alias "$ALIAS_PUBLICATION" \
    -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Evermind Mobile, O=Jordan Jacquot" \
    -storepass:env EV_MDP_PUBLICATION -keypass:env EV_MDP_PUBLICATION > /dev/null

echo "3. La lignée : l'ancienne clé autorise la nouvelle à la remplacer"
apksigner rotate --out "$LIGNEE" \
    --old-signer --ks "$CLE_ANCIENNE" --ks-key-alias "$ALIAS_ANCIENNE" --ks-pass env:EV_MDP_ANCIENNE \
    --new-signer --ks "$CLE_PUBLICATION" --ks-key-alias "$ALIAS_PUBLICATION" --ks-pass env:EV_MDP_PUBLICATION
apksigner lineage --in "$LIGNEE" --print-certs | grep -E 'certificate (DN|SHA-256)'

echo
echo "C'est fait. Maintenant, SAUVEGARDE le dossier $DOSSIER_CLES (ses 3 fichiers) hors du PC :"
echo "une clé USB rangée à part, et une deuxième copie ailleurs. Jamais sur GitHub."
echo "Sans ce dossier et ton mot de passe, les téléphones ne pourront plus jamais être mis à jour."
