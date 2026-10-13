# Evermind Mobile

L'agenda qui se souvient de tout pour toi, sur Android. Version téléphone d'[Evermind](https://github.com/JordanJacquot/Evermind).
Tout reste sur le téléphone : la dictée, la voix et l'assistant tournent en local. Rien n'est envoyé sur Internet
(Internet sert seulement à télécharger une fois les modèles de la voix, comme sur PC).

## Les choix

- **Android seulement**, en Kotlin avec Jetpack Compose (le langage et l'outil officiels d'Android).
- **Agenda propre au téléphone** pour commencer (pas de synchronisation avec le PC ; elle viendra plus tard).
- **Diffusion** : un `.apk` sur GitHub d'abord, le Play Store ensuite.

## Le plan

1. **L'agenda de base** : calendrier, liste « À venir », fiche, ajout et modification à la main, rendez-vous enregistrés sur le téléphone.
2. **Comprendre les phrases** : la lecture des rendez-vous en français d'Evermind (`comprendre.ps1`) réécrite en Kotlin,
   vérifiée contre les catalogues d'Evermind (près de 3 000 phrases et 144 demandes à l'assistant).
3. **Rappels** : notifications à l'heure exacte (la veille, le matin même, 1 h avant...).
4. **La voix** : dictée Whisper et voix Kokoro (sherpa-onnx pour Android), « OK Evermind » quand l'app est ouverte ou en charge.
5. **L'assistant** (en option, téléphones récents) : un petit modèle local ; puis thèmes, couleurs des sortes, horaires de travail.
6. **Publication** : `.apk` signé sur GitHub, puis Play Store (compte développeur, 12 testeurs pendant 14 jours, page de confidentialité).

## Développer

Il faut [Android Studio](https://developer.android.com/studio) et un téléphone Android en mode développeur (ou l'émulateur d'Android Studio).
Ouvrir le dossier dans Android Studio, attendre la synchronisation Gradle, puis ▶ (Run).

- `gradlew assembleDebug` : construit `app/build/outputs/apk/debug/app-debug.apk`.
- `gradlew test` : les tests sur PC, dont la lecture des phrases comparée à Evermind PC (3 072 phrases).
- `gradlew connectedDebugAndroidTest` : la même comparaison sur le téléphone ou l'émulateur branché
  (Android lit les expressions régulières autrement que la JVM).
- `outils\exporter-phrases.ps1` : refait le fichier de référence de ces tests à partir du dépôt Evermind PC
  (à relancer quand la lecture change sur PC, puis porter le changement en Kotlin jusqu'à ce que les tests passent).
- Android 8 minimum (`minSdk 26`). La permission Internet ne sert qu'à « Installer la voix ».
- Au premier build, Gradle télécharge sherpa-onnx 1.13.8 (son `.aar` n'est pas sur Maven), empreinte vérifiée,
  dans `.bibliotheques/`.

### Où est quoi

Le code est dans `app/src/main/java/io/github/jordanjacquot/evermind/` :

- `comprendre/` : lire un rendez-vous écrit en français (`lirePhrase`, portage de `comprendre.ps1`), les répétitions
  (`prochaineFois`) et le passage d'une phrase comprise aux rendez-vous à créer.
- `donnees/` : le rendez-vous (`RendezVous`, mêmes champs que le `rendez-vous.json` d'Evermind PC), les sortes
  (`Sorte`, mêmes codes, icônes et couleurs), la base Room et `DepotRendezVous`, seul passage pour lire et écrire.
- `ui/agenda/` : l'écran principal (barre où l'on écrit un rendez-vous, calendrier du mois, « À venir » ou le jour choisi).
- `ui/fiche/` : la fiche d'un rendez-vous (détails, choses à préparer, supprimer).
- `ui/formulaire/` : ajouter ou modifier à la main.
- `ui/theme/` : couleurs ; `ui/Textes.kt` : dates et heures écrites en français (« demain à 15 h 30 ») ;
  `ui/Navigation.kt` : les écrans et le passage de l'un à l'autre.

- `voix/` : la dictée du téléphone, les modèles (`Modeles`, versions et empreintes fixées) et leur installation,
  Whisper, Kokoro et l'écoute de « OK Evermind » (Vosk) ; `Voix` les réunit, et ce qu'Evermind répond.
- `rappels/` : les rappels (comme sur PC), l'alarme Android qui les donne à l'heure, et leurs notifications.
- `ui/agenda/Conversation.kt` : parler à Evermind (micro ou « OK Evermind ») ; `ui/reglages/` : les réglages.

L'assistant (étape 5) aura son dossier `assistant/`.

## État

- **Étape 1** faite : l'agenda enregistré sur le téléphone, calendrier, « À venir », fiche, formulaire.
- **Étape 2** faite : on écrit « mardi 15 h dentiste », « foot tous les lundis à 19 h sauf en novembre » ou
  « ElleFest du 27 au 29 octobre » ; ce qui est compris s'affiche pendant qu'on écrit, puis le rendez-vous est créé
  (« Annuler » juste après). Il manque l'heure ? Evermind le dit, ou on complète dans le formulaire.
  La lecture donne exactement la même chose qu'Evermind PC sur les 3 072 phrases de ses catalogues.
  Les rendez-vous qui reviennent s'affichent à chaque fois dans le calendrier.
- **Rappels** (étape 3), comme sur PC : la veille à 20 h, le matin même à 8 h, 1 h avant (à l'heure pour une chose
  à faire) ; chacun se coupe dans les Réglages. Chaque rendez-vous peut avoir les siens (formulaire, ou « rappelle-moi
  2 jours avant »), ou aucun. Notification aux couleurs de la sorte, avec « Voir » et « Dans 10 min » ; à l'heure
  exacte même Evermind fermé, et après un redémarrage du téléphone.
- **La voix** (étape 4), comme sur PC : Réglages, « Installer la voix » (510 Mo, une seule fois) ; le micro utilise
  alors Whisper small, Evermind crée le rendez-vous et répond avec Kokoro (« C'est noté : Foot, tous les lundis à
  19 heures. »), avec les 5 voix du PC. « Installer « OK Evermind » » (42 Mo) : dire « OK Evermind » quand l'agenda
  est à l'écran, puis sa demande. Sans la voix installée, le micro utilise la dictée hors ligne du téléphone.
  Pas encore : « OK Evermind, ajoute... » d'une seule traite (il faut attendre le bip), ni l'écoute en arrière-plan.
- Pas encore : les horaires de travail en postes (« je suis du matin lundi et mardi ») et les demandes à l'assistant
  (« décale le dentiste à jeudi »), qui viendront avec l'assistant (étape 5).
