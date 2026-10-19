# L'assistant d'Evermind Mobile dans Evermind PC

Evermind PC utilise le même assistant que le téléphone : les mêmes règles (`assistant/Ordres.kt`), les mêmes
contrôles (`Assistant.kt`), les mêmes actions (`Actions.kt`) et le même modèle entraîné (Evermind 0.6B). Une
amélioration faite ici profite donc aux deux, dès qu'un nouveau paquet est publié.

## Le programme

Le module `assistant-pc` compile les fichiers de l'app qui ne dépendent pas d'Android (liste dans
`assistant-pc/build.gradle.kts`) avec deux fichiers propres au PC :

- `pc/Principal.kt` : une demande par ligne sur l'entrée, une réponse par ligne sur la sortie (JSON, UTF-8).
  - Demande : `{"texte": "...", "agenda": [<rendez-vous.json>], "maintenant": "2026-10-19T14:00"}`.
  - Réponse : `{"compris": true, "dit": "...", "ecrit": "...", "change": true, "aCorriger": null, "agenda": [...]}`.
  - Si rien n'est compris : `{"compris": false}`.
  - Le programme reste ouvert : il se souvient de la conversation (« lequel ? ») et de quoi annuler.
  - Les rendez-vous sont au format du fichier d'Evermind PC. Ce que le téléphone ne connaît pas (les dossiers à ouvrir)
    est repris tel quel.
- `pc/Serveur.kt` : le modèle, lancé avec le `llama-server` d'Evermind PC à la première phrase qui en a besoin, et
  arrêté après 10 minutes sans question.

## Publier un paquet

1. `bash outils/assistant-pc.sh <N>` : construit `app/build/evermind-assistant-pc-<N>.zip` et donne son empreinte.
2. `gh release create assistant-pc-v<N> app/build/evermind-assistant-pc-<N>.zip --prerelease` : toujours un nouveau
   numéro. Un fichier remplacé sous le même nom reste servi un moment dans son ancienne version, et le téléchargement
   d'Evermind PC échoue alors.
3. Dans Evermind PC (`Evermind/fichiers/modules/voix.ps1`), mettre à jour `$AssistantLib`, l'adresse, l'empreinte, la
   taille et `Vers` du « programme de l'assistant ». Les anciennes versions sont effacées au démarrage.

## Côté Evermind PC

- Le pack « ia » contient : `llama-server` (llama.cpp), le modèle (`assistant-v2` de ce dépôt), Java (Temurin 21
  d'Adoptium) et ce programme. L'ancien Qwen3 4B (2,5 Go) est effacé.
- `cerveau.ps1` lance le programme (`Start-Cerveau`), lui envoie la phrase et l'agenda (`Invoke-Commande`), puis
  remplace l'agenda par celui qu'il rend, après l'avoir mis de côté pour « annule » (`Invoke-ReponseAssistant`).
- Deux fonctions restent lues par les règles du PC : ouvrir les dossiers d'un rendez-vous et les postes de travail.
- Essai par le même chemin que l'appli : `.\outils\essai-assistant.ps1`. Il installe le pack dans `_test` s'il manque.
- Le Qwen3 4B sert encore la nuit pour écrire les phrases d'entraînement. Il est gardé dans
  `%LOCALAPPDATA%\Evermind-entrainement\modeles`.
