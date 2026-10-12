# Evermind Mobile

L'agenda qui se souvient de tout pour toi, sur Android. Version téléphone d'[Evermind](https://github.com/JordanJacquot/Evermind).
Tout reste sur le téléphone : la dictée, la voix et l'assistant tournent en local. Rien n'est envoyé sur Internet.

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
- `gradlew test` : les tests (format des dates, compatibilité du fichier avec Evermind PC).
- Android 8 minimum (`minSdk 26`), aucune permission Internet.

### Où est quoi

Le code est dans `app/src/main/java/io/github/jordanjacquot/evermind/` :

- `donnees/` : le rendez-vous (`RendezVous`, mêmes champs que le `rendez-vous.json` d'Evermind PC), les sortes
  (`Sorte`, mêmes codes, icônes et couleurs), la base Room et `DepotRendezVous`, seul passage pour lire et écrire.
- `ui/agenda/` : l'écran principal (calendrier du mois, « À venir » ou le jour choisi).
- `ui/fiche/` : la fiche d'un rendez-vous (détails, choses à préparer, supprimer).
- `ui/formulaire/` : ajouter ou modifier à la main.
- `ui/theme/` : couleurs ; `ui/Textes.kt` : dates et heures écrites en français (« demain à 15 h 30 ») ;
  `ui/Navigation.kt` : les écrans et le passage de l'un à l'autre.

Les étapes suivantes auront chacune leur dossier : `comprendre/` (2), `rappels/` (3), `voix/` (4), `assistant/` (5).

## État

Étape 1 en cours : la structure du projet, l'agenda enregistré sur le téléphone et ses trois écrans.
Les rendez-vous répétés ne s'affichent qu'à leur première fois (les suivantes viennent avec l'étape 2).
