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
- Le catalogue de l'assistant du PC (125 demandes) avec l'IA du PC : `EVERMIND_LLAMA_SERVEUR` (llama-server.exe) et
  `EVERMIND_LLAMA_MODELE` (.gguf), puis `gradlew testDebugUnitTest --tests *CatalogueAssistantTest*` ; avec
  `EVERMIND_SANS_IA=1`, les règles d'Evermind seules ; `EVERMIND_CATALOGUE=examen`, `contre-examen`, `bases`, `bases-verif`, `quotidien`, `quotidien-verif`, `complexe` ou `complexe-verif` : les phrases
  nouvelles au lieu de celles du PC.
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
- `ui/theme/` : la DA d'Evermind PC (fond de nuit, panneaux en verre, boutons en dégradé, icônes des sortes, logo) et ses
  5 thèmes, avec la même « traduction » des couleurs que sur PC ; `ui/Textes.kt` : dates et heures écrites en français (« demain à 15 h 30 ») ;
  `ui/Navigation.kt` : les écrans et le passage de l'un à l'autre.

- `voix/` : la dictée du téléphone, les modèles (`Modeles`, versions et empreintes fixées) et leur installation,
  Whisper, Kokoro et l'écoute de « OK Evermind » (Vosk) ; `Voix` les réunit, et ce qu'Evermind répond.
- `rappels/` : les rappels (comme sur PC), l'alarme Android qui les donne à l'heure, et leurs notifications.
- `ui/agenda/Conversation.kt` : parler à Evermind (micro ou « OK Evermind ») ; `ui/reglages/` : les réglages.

- `assistant/` : l'assistant (portage de `cerveau.ps1`) : consignes, contrôles, actions, choix du modèle selon la
  mémoire (`Cerveau`) ; `app/src/main/cpp/` : le moteur llama.cpp compilé pour Android.

## État

- **Étape 1** faite : l'agenda enregistré sur le téléphone, calendrier, « À venir », fiche, formulaire.
- **Étape 2** faite : on écrit « mardi 15 h dentiste », « foot tous les lundis à 19 h sauf en novembre » ou
  « ElleFest du 27 au 29 octobre » ; ce qui est compris s'affiche pendant qu'on écrit, puis le rendez-vous est créé
  (« Annuler » juste après). Il manque l'heure ? Evermind le dit, ou on complète dans le formulaire.
  La lecture donne exactement la même chose qu'Evermind PC sur les 3 072 phrases de ses catalogues.
  Les rendez-vous qui reviennent s'affichent à chaque fois dans le calendrier.
- **La DA d'Evermind PC** : fond de nuit et ses lueurs, panneaux en verre, boutons pilule en dégradé, logo E dans son
  anneau, cartes et fiche aux couleurs des sortes, calendrier en tuiles ; les 5 thèmes du PC (Néon, Crépuscule,
  Terminal, Arcade, Rubis) au choix dans les Réglages, appliqués tout de suite.
- **Rappels** (étape 3), comme sur PC : la veille à 20 h, le matin même à 8 h, 1 h avant (à l'heure pour une chose
  à faire) ; chacun se coupe dans les Réglages. Chaque rendez-vous peut avoir les siens (formulaire, ou « rappelle-moi
  2 jours avant »), ou aucun. Notification aux couleurs de la sorte, avec « Voir » et « Dans 10 min » ; à l'heure
  exacte même Evermind fermé, et après un redémarrage du téléphone.
- **La voix** (étape 4), comme sur PC : Réglages, « Installer la voix » (510 Mo, une seule fois) ; le micro utilise
  alors Whisper small, Evermind crée le rendez-vous et répond avec Kokoro (« C'est noté : Foot, tous les lundis à
  19 heures. »), avec les 5 voix du PC. « Installer « OK Evermind » » (42 Mo) : dire « OK Evermind » quand l'agenda
  est à l'écran, puis sa demande. Sans la voix installée, le micro utilise la dictée hors ligne du téléphone.
  Après la dictée, Evermind corrige les noms mal entendus en les comparant « à l'oreille » avec les 35 000 communes
  de France, les métiers et spécialités médicales, les enseignes et les mots de ton agenda (« Oftalmologue »,
  « à messe » pour Metz, « le clair » pour Leclerc, « Epinal » sans accent) : `voix/Correcteur.kt`, lexique dans
  `app/src/main/assets/lexique/` (refait par `outils/lexique-communes.py`).
  Pas encore : « OK Evermind, ajoute... » d'une seule traite (il faut attendre le bip), ni l'écoute en arrière-plan.
- **L'assistant** (étape 5, en cours) : « décale le dentiste à jeudi », « supprime les raids de novembre », « j'ai payé
  le loyer », « qu'est-ce que j'ai demain ? », « supprime le coiffeur et mets le dentiste à 16 h ». Evermind comprend
  seul ces demandes (`assistant/Ordres.kt`), tout de suite et sur tous les téléphones, sans IA : 125 demandes sur 125
  du catalogue du PC, et 364 sur 364 de huit lots de phrases nouvelles : demandes de tous les jours (« supprime
  tout sauf le dentiste », « mon prochain rendez-vous »), dites à l'oral (« euh, le dentiste, tu peux le mettre jeudi
  plutôt ? »), à calculer (« dans combien de jours c'est l'anniversaire de Léa ? », « j'ai quoi après le dentiste ? »),
  en plusieurs fois (« ajoute tennis samedi », puis « à 10 h » ; « supprime le foot », puis « et le coiffeur aussi »),
  complexes (« deux jours avant l'anniversaire de Léa », « comme je suis malade, annule le foot », « mets le dentiste
  jeudi, non vendredi », « si je décale le foot à vendredi, ça tombe sur quelque chose ? », « annule le foot et le
  coiffeur, mais garde le dentiste »).
  En plus, pour les phrases qu'il ne reconnaît pas, une IA sur le téléphone (llama.cpp, comme sur PC), choisie selon
  sa mémoire pour ne jamais le ralentir : Qwen3 4B à partir de 12 Go, Qwen3.5 2B de 6 à 11 Go, Qwen3.5 0.8B de 4 à
  5 Go (avec un avertissement), rien en dessous. Ses consignes sont lues une seule fois, en arrière-plan, puis gardées
  sur le téléphone. Evermind vérifie tout avant d'agir, et « Annuler » revient en arrière.
  Les heures de chaque poste de travail (matin, journée, après-midi, soir, nuit) se règlent dans les Réglages, ou
  à la voix (« mon poste du matin, c'est de 7 h à 15 h ») ; « je suis du matin lundi et mardi » les utilise.
