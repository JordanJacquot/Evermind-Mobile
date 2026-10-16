# Les mesures de l'assistant

Une ligne par série, à chaque tour (`bash outils/mesurer.sh [modèle.gguf] [nom du tour]`, voir docs/PLAN-1.0.md).
« app » : comme dans l'application, les règles d'abord, puis le modèle.

| Tour | Série | Type | Rôle | Règles seules | Modèle seul | App |
|---|---|---|---|---|---|---|
| assistant-v1 | lots écrits à la main | base | — | 874 / 874 (100 %) | — | — |
| assistant-v1 | serie1 | complexe | diagnostic | 126 / 187 (67 %) | 140 / 187 (74 %) | 140 / 187 (74 %) |
| assistant-v1 | base1 | base | diagnostic | 163 / 172 (94 %) | 153 / 172 (88 %) | 164 / 172 (95 %) |
| assistant-v1 | base2 | base | diagnostic | 155 / 160 (96 %) | 142 / 160 (88 %) | 156 / 160 (97 %) |
| assistant-v1 | controle1-base | base | controle | 112 / 131 (85 %) | 120 / 131 (91 %) | 117 / 131 (89 %) |
| assistant-v1 (finales) | serie1 | complexe | diagnostic | 126 / 187 (67 %) | 141 / 187 (75 %) | 139 / 187 (74 %) |
| assistant-v1 (finales) | base1 | base | diagnostic | 163 / 172 (94 %) | 150 / 172 (87 %) | 164 / 172 (95 %) |
| assistant-v1 (finales) | base2 | base | diagnostic | 155 / 160 (96 %) | 143 / 160 (89 %) | 156 / 160 (97 %) |
| assistant-v1 (finales) | controle1-base | base | controle | 112 / 131 (85 %) | 121 / 131 (92 %) | 117 / 131 (89 %) |
| assistant-v1 (finales) | finale-complexe | complexe | finale | 105 / 171 (61 %) | 140 / 171 (81 %) | 122 / 171 (71 %) |
| assistant-v1 (finales) | finale-base | base | finale | 141 / 169 (83 %) | 150 / 169 (88 %) | 148 / 169 (87 %) |
| nuit1 | lots écrits à la main | base | — | 874 / 874 (100 %) — 2 échecs du banc (heure réelle), corrigés | — | — |
| nuit1 | serie1 | complexe | diagnostic | 126 / 187 (67 %) | 150 / 187 (80 %) | 140 / 187 (74 %) |
| nuit1 | base1 | base | diagnostic | 163 / 172 (94 %) | 156 / 172 (90 %) | 165 / 172 (95 %) |
| nuit1 | base2 | base | diagnostic | 155 / 160 (96 %) | 143 / 160 (89 %) | 155 / 160 (96 %) |
| nuit1 | controle1-base | base | controle | 112 / 131 (85 %) | 119 / 131 (90 %) | 117 / 131 (89 %) |
