# La clé de publication d'Evermind Mobile

Jusqu'à la version 0.2.4, les APK publiés étaient des versions « de débogage », signées avec la clé de débogage
d'Android Studio de ce PC (`~/.android/debug.keystore`). Le jour où cette clé disparaît (PC réinstallé, dossier effacé),
les téléphones refusent toute mise à jour : il faudrait désinstaller, et l'agenda serait perdu.

Désormais :
- la version publiée est une vraie version de publication (`assembleRelease`, pas « débogable ») ;
- elle est signée avec une **clé de publication** dédiée, gardée hors du dépôt et sauvegardée hors du PC ;
- une **lignée** (rotation de clé d'Android, APK Signature Scheme v3) dit aux téléphones que la nouvelle clé remplace
  l'ancienne. Personne ne réinstalle, l'agenda reste.

## Une seule fois : créer la clé (Jordan)

1. Vérifier que la clé de débogage de ce PC est bien celle des versions publiées. Le script le vérifie lui-même : il
   refuse si l'empreinte n'est pas `5d16494a34b391076b4ee12b8cd318565b63865a88d23a84432010cd94482689` (celle des APK
   construits sur ce PC). Pour en être sûr, sur l'APK 0.2.4 téléchargé depuis GitHub :
   `java -jar <SDK>/build-tools/<version>/lib/apksigner.jar verify --print-certs Evermind-Mobile-0.2.4-beta.apk`
   doit montrer cette même empreinte (« Signer #1 certificate SHA-256 digest »).
2. Dans Git Bash, à la racine du dépôt : `bash outils/creer-cle-publication.sh`
   - il demande le mot de passe de la clé de débogage (si tu ne l'as jamais changé : celui par défaut des clés de
     débogage d'Android, page « Sign your app » de developer.android.com) ;
   - puis un **nouveau** mot de passe pour la clé de publication (12 caractères ou plus), deux fois. Note-le dans un
     gestionnaire de mots de passe ;
   - les mots de passe ne s'affichent pas et ne sont écrits dans aucun fichier.
3. Il crée le dossier `~/.evermind-signature` (hors du dépôt), avec 3 fichiers :
   - `evermind-publication.jks` : la clé de publication (RSA 4096, valable 100 ans) ;
   - `cle-debogage-android.keystore` : une copie de l'ancienne clé ;
   - `lignee.bin` : la lignée (l'ancienne clé autorise la nouvelle, avec le droit de garder les données).
4. **Sauvegarder ce dossier hors du PC** : une clé USB rangée à part, et une deuxième copie ailleurs (pas sur GitHub,
   pas dans le dépôt). Sans ce dossier et le mot de passe, plus aucune mise à jour n'est possible.

Les deux clés restent nécessaires pour toujours : les téléphones sous Android 8 ne lisent pas la lignée (v3), ils
reconnaissent l'ancienne clé ; Android 9 et plus récent reconnaissent la nouvelle grâce à la lignée.

## À chaque publication

`bash outils/publier.sh <version> "<ce qui change>"` comme avant. Il construit `assembleRelease`, puis
`outils/signer-apk.sh` signe l'APK et demande les deux mots de passe :
- v2 : l'ancienne clé (téléphones sous Android 8) ;
- v3, Android 9 et plus récent : la clé de publication, avec la lignée (`--rotation-min-sdk-version 28`).

Avant de publier, il vérifie : signature valable depuis Android 8, l'ancienne clé pour Android 8, la nouvelle pour
Android 9 et plus, l'ancienne dans la lignée avec le droit de garder les données, et l'APK n'est pas « débogable ».
Si une vérification échoue, rien n'est publié.

## Ce qui a été essayé (sans téléphone)

Sur des clés jetables, jamais les vraies :
- le script crée la clé et la lignée ; il refuse une autre ancienne clé que celle des versions publiées, et refuse de
  refaire une clé qui existe déjà ;
- un mauvais mot de passe : rien n'est signé ;
- la règle d'Android pour une mise à jour (même clé, ou ancienne clé dans la lignée avec le droit de garder les
  données), rejouée avec la bibliothèque d'apksigner pour Android 8 à 16 : une version signée par l'ancienne clé est
  mise à jour par la version tournée, puis la version tournée par la suivante. Contre-épreuve : la même version signée
  avec la seule nouvelle clé est refusée partout.

Reste à essayer sur un vrai téléphone (avec les vraies clés, une fois créées) : installer la 0.2.4, ajouter un
rendez-vous, installer la première version signée par `signer-apk.sh` par-dessus : la mise à jour passe, l'agenda reste.

## La réduction du code (R8, minify)

Coupée (`isMinifyEnabled = false`). La voix passe par JNI (sherpa-onnx) et JNA (Vosk), qui ont besoin de règles « keep »
(`proguard-rules.pro`, vide). Avant de l'activer : écrire ces règles, construire `assembleRelease`, et essayer la
dictée, la voix et « OK Evermind » sur un téléphone.
