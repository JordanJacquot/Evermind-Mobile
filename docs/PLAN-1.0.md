# Plan d'entraînement de l'assistant, jusqu'à la 1.0

Décision (16 octobre 2026) : un seul modèle, **Evermind 0.6B**, entraîné pour Evermind. Les autres IA (Qwen3 4B,
Qwen3.5 2B, Qwen3.5 0.8B) sont mises de côté. Evermind Mobile sera « sortable » (version 1.0) quand les deux seuils
ci-dessous seront atteints.

## Les seuils de la 1.0

| | Seuil | Mesuré sur |
|---|---|---|
| Demandes de base (créer, supprimer, déplacer, questions simples...) | **100 %** | les 18 lots écrits à la main, et les séries « base » neuves |
| Demandes complexes (phrases longues, libres, dictées) | **90 %** | une série complexe neuve, jamais regardée |
| Erreurs dangereuses (supprimer ou changer le mauvais rendez-vous) | **0** | partout |
| Tes vraies phrases, dictées sur ton téléphone | **90 %** | au moins 100 phrases |
| Sur un téléphone de 3 Go | réponse en quelques secondes, sans plantage | téléphone réel |

Précisions :

- **100 % sur la base** : sur les lots écrits à la main et sur les séries « base » après correction. Sur la série
  finale jamais vue, toute erreur sur un ordre de base est corrigée, puis vérifiée sur une nouvelle série. Le seuil
  est atteint quand une série neuve passe entièrement.
- **Seules les phrases valables comptent** : une phrase générée qui ne dit pas ce qu'elle devrait (« dental à 17 h »)
  est écartée, avec la raison notée. Les phrases difficiles mais claires restent.
- **Tout se mesure comme dans l'app** : les règles d'abord, puis le modèle.

Où on en est (16 octobre) : base 95 à 97 % (séries base1 et base2), lots 873/874 ; complexe 75 % (série 1, qui sert
maintenant au diagnostic, donc n'est plus une mesure à l'aveugle).

## Les séries de test : trois niveaux, pour ne pas se mentir

À chaque tour, on corrige en regardant les échecs. Une série regardée ne mesure plus rien d'honnête. Donc :

1. **Diagnostic** : série 1 et séries « base » 1 et 2. On les regarde, on corrige dessus.
2. **Contrôle** : une série neuve à chaque tour (complexe et base), mesurée **avant** toute retouche. C'est le chiffre
   du tour.
3. **Finale** : une série complexe et une série base, générées une fois, **jamais regardées** avant le passage à la
   1.0. Avec tes vraies phrases, elles décident de la sortie.

Les séries sont écrites par le Qwen3 4B du PC, avec d'autres consignes et d'autres agendas que les exemples
d'entraînement, pour que le modèle ne soit pas jugé sur ce qu'il a appris par cœur.

## Étape 0 : préparer (de jour, sans charger le PC)

- **Un seul modèle dans l'app.** Seul Evermind 0.6B est proposé dans Réglages > Assistant, dès 3 Go. Celui qui avait
  un autre modèle installé se le voit proposer à la place, et l'ancien fichier est effacé pour rendre la place.
- **Un seul banc de mesure** : un script qui sort le tableau complet (base, complexe, seul et avec les règles), et
  l'ajoute à un historique (`docs/MESURES.md`), pour voir la progression de tour en tour.
- **Réparer le test qui dépend du jour** (« Qu'est-ce que j'ai samedi ? / Mets-le à 18 h » suppose que le foot tombe
  un samedi).
- **Générer les séries finales**, puis les mettre de côté sans les ouvrir.

## Étape 1 : des exemples propres (nuit 1)

La cause du plafond actuel : les exemples des tours 1 à 3, empilés, contredisent les nouveaux (« après décembre »
appris comme « à partir de décembre »). Donc :

- **Tout refaire avec la fabrique actuelle**, à chaque tour : environ 10 000 tâches, soit 25 000 phrases. On
  n'empile plus.
- **Toujours repartir du Qwen3 0.6B d'origine**, jamais du modèle du tour précédent.
- **Plus de variété dans les phrases** : plusieurs consignes pour le 4B qui les écrit (oral, écrit pressé, dictée
  sans ponctuation, personne âgée, langage SMS), et des longueurs variées.
- **Un juge plus strict** : une phrase est gardée seulement si elle demande exactement l'intention, sans ambiguïté.

## Étape 2 : décharger le modèle des calculs (de jour)

Le modèle se trompe sur ce qu'Evermind sait faire exactement : l'âge de Léa, le jour de la semaine, « deux jours
avant… », la liste des rendez-vous d'une journée. Il répond aujourd'hui avec des phrases qu'il écrit lui-même.

- **Nouvelle action « question »** : le modèle dit seulement *ce qui est demandé* (quand, où, qui, l'âge, les notes,
  ce qu'il faut prendre, le programme d'un jour, le prochain rendez-vous) et *sur quoi* (le rendez-vous ou la
  période). Evermind calcule la réponse, avec le code déjà utilisé par les règles.
- **Les dates telles que l'utilisateur les dit** : le modèle recopie « jeudi », « le 12 », « après décembre », et
  c'est Evermind qui calcule la date.
- La fabrique et la vérification suivent : chaque exemple de question est vérifié par la réponse qu'Evermind en tire.

## Étape 3 : la boucle sur les erreurs (nuits 2 à 5)

Chaque nuit :

1. Fabriquer les exemples, entraîner, convertir (environ 3 heures).
2. Mesurer la série de contrôle du tour : c'est le chiffre retenu.
3. Classer les échecs par famille (création, modification, suppression, question, rien à faire).

Le lendemain :

4. Pour chaque famille qui rate : ajouter les cas à la fabrique (nouvelle tournure, nouveau piège), ou corriger la
   vérification si elle se trompait.
5. Les échecs sur des ordres de base vont aux règles (`Ordres.kt`), pas au modèle : ce sont elles qui garantissent les
   100 %.
6. Vérifier qu'aucune régression n'apparaît (les 18 lots, les séries base).

On s'arrête quand la série de contrôle dépasse 90 % deux nuits de suite.

## Étape 4 : la vraie vie (en parallèle)

- **Tes phrases** : tu utilises l'app normalement. Un bouton « Ce n'est pas ce que je voulais » (prévu à l'étape 0)
  garde la phrase et la réponse **sur le téléphone**, et tu les exportes quand tu veux. Rien ne part tout seul.
- **Les fautes de dictée** : la fabrique imite ce que Whisper produit vraiment (sans ponctuation, « à » et « a »
  confondus, nombres en lettres comme « dix-huit heures », noms mal écrits).
- **Les conversations** : « ajoute tennis samedi », puis « à 10 h » ; « supprime le foot », puis « et le coiffeur
  aussi ».
- **Quand c'est ambigu, demander** : « Lequel ? Le dentiste ou le kiné ? » plutôt que deviner. Une question posée
  n'est pas une erreur ; une suppression au hasard, si.

## Étape 5 : la validation de la 1.0

1. Séries finales (base et complexe), jamais regardées : 100 % et 90 %, aucune erreur dangereuse.
2. Tes vraies phrases : au moins 90 %.
3. Sur ton téléphone et sur un téléphone de 3 Go (ou l'émulateur à 3 Go) : temps de réponse, mémoire, batterie,
   installation du modèle depuis l'app.
4. Si tout passe : la 1.0.

## La sortie de la 1.0

- **Un APK « release »** (non débogable), signé avec une **clé définitive**, gardée en lieu sûr (sans elle, plus
  aucune mise à jour possible).
- `versionName` 1.0.0, une version GitHub avec l'APK et son empreinte SHA-256 dans la description.
- Le modèle publié dans sa propre version (`assistant-v2`...), son empreinte écrite dans l'app.
- Le README à jour avec les chiffres mesurés.

## Le calendrier

| Quand | Quoi |
|---|---|
| Jour 1 | Étape 0, et étape 2 (l'action « question ») |
| Nuit 1 | Étape 1 : premier entraînement sur des exemples propres |
| Jours et nuits 2 à 5 | Étape 3, la boucle ; étape 4 avec tes phrases |
| Ensuite | Étape 5, puis la 1.0 |

Le nombre de nuits dépend des progrès. Chaque tour est noté dans `docs/MESURES.md` : on voit vite si on avance ou si
on plafonne. Si on plafonne deux nuits de suite malgré des exemples propres, on en reparle : piste suivante, un
entraînement complet (toutes les couches) au lieu de LoRA, ou des exemples écrits par un plus gros modèle.
