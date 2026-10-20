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
| nuit1 + 2 bis | lots écrits à la main | base | — | 874 / 874 (100 %) | — | 874 / 874 (100 %) |
| nuit1 + 2 bis | serie1 | complexe | diagnostic | 126 / 187 (67 %) | 149 / 187 (79 %) | 152 / 187 (81 %) |
| nuit1 + 2 bis | base1 | base | diagnostic | 163 / 172 (94 %) | 154 / 172 (89 %) | 165 / 172 (95 %) |
| nuit1 + 2 bis | base2 | base | diagnostic | 155 / 160 (96 %) | 145 / 160 (90 %) | 155 / 160 (96 %) |
| nuit1 + 2 bis | controle1-base | base | controle | 112 / 131 (85 %) | 117 / 131 (89 %) | 116 / 131 (88 %) |
| nuit1 + 2 bis | controle1-complexe | complexe | controle | 110 / 165 (66 %) | 147 / 165 (89 %) | 142 / 165 (86 %) |
| nuit1 + 2 bis + règles base + étape 2 | lots écrits à la main | base | — | 874 / 874 (100 %) | — | 874 / 874 (100 %) |
| nuit1 + 2 bis + règles base + étape 2 | serie1 | complexe | diagnostic | 126 / 187 (67 %) | 148 / 187 (79 %) | 154 / 187 (82 %) |
| nuit1 + 2 bis + règles base + étape 2 | base1 | base | diagnostic | 170 / 172 (98 %) | 154 / 172 (89 %) | 171 / 172 (99 %) |
| nuit1 + 2 bis + règles base + étape 2 | base2 | base | diagnostic | 160 / 160 (100 %) | 143 / 160 (89 %) | 160 / 160 (100 %) |
| nuit1 + 2 bis + règles base + étape 2 | base3 | base | diagnostic | 161 / 161 (100 %) | 147 / 161 (91 %) | 161 / 161 (100 %) |
| nuit1 + 2 bis + règles base + étape 2 | base4 | base | diagnostic | 154 / 154 (100 %) | 140 / 154 (90 %) | 154 / 154 (100 %) |
| nuit1 + 2 bis + règles base + étape 2 | controle1-base | base | controle | 118 / 131 (90 %) | 119 / 131 (90 %) | 121 / 131 (92 %) |
| nuit1 + 2 bis + règles base + étape 2 | controle1-complexe | complexe | controle | 110 / 165 (66 %) | 145 / 165 (87 %) | 141 / 165 (85 %) |
| nuit2 | lots écrits à la main | base | — | 874 / 874 (100 %) | — | 873 / 874 (99 %) |
| nuit2 | serie1 | complexe | diagnostic | 126 / 187 (67 %) | 139 / 187 (74 %) | 149 / 187 (79 %) |
| nuit2 | base1 | base | diagnostic | 170 / 172 (98 %) | 131 / 172 (76 %) | 170 / 172 (98 %) |
| nuit2 | base2 | base | diagnostic | 160 / 160 (100 %) | 125 / 160 (78 %) | 160 / 160 (100 %) |
| nuit2 | base3 | base | diagnostic | 161 / 161 (100 %) | 133 / 161 (82 %) | 161 / 161 (100 %) |
| nuit2 | base4 | base | diagnostic | 154 / 154 (100 %) | 121 / 154 (78 %) | 154 / 154 (100 %) |
| nuit2 | controle1-base | base | controle | 118 / 131 (90 %) | 104 / 131 (79 %) | 120 / 131 (91 %) |
| nuit2 | controle1-complexe | complexe | controle | 110 / 165 (66 %) | 126 / 165 (76 %) | 135 / 165 (81 %) |
| nuit2 + schéma corrigé | lots écrits à la main | base | — | 874 / 874 (100 %) | — | 872 / 874 (99 %) |
| nuit2 + schéma corrigé | serie1 | complexe | diagnostic | 126 / 187 (67 %) | 161 / 187 (86 %) | 152 / 187 (81 %) |
| nuit2 + schéma corrigé | base1 | base | diagnostic | 170 / 172 (98 %) | 152 / 172 (88 %) | 170 / 172 (98 %) |
| nuit2 + schéma corrigé | base2 | base | diagnostic | 160 / 160 (100 %) | 147 / 160 (91 %) | 160 / 160 (100 %) |
| nuit2 + schéma corrigé | base3 | base | diagnostic | 161 / 161 (100 %) | 150 / 161 (93 %) | 161 / 161 (100 %) |
| nuit2 + schéma corrigé | base4 | base | diagnostic | 154 / 154 (100 %) | 136 / 154 (88 %) | 154 / 154 (100 %) |
| nuit2 + schéma corrigé | controle1-base | base | controle | 118 / 131 (90 %) | 119 / 131 (90 %) | 121 / 131 (92 %) |
| nuit2 + schéma corrigé | controle1-complexe | complexe | controle | 110 / 165 (66 %) | 148 / 165 (89 %) | 140 / 165 (84 %) |
| nuit2 + correctifs | serie1 | complexe | diagnostic | 126 / 187 (67 %) | 162 / 187 (86 %) | 153 / 187 (81 %) |
| nuit2 + correctifs | base1 | base | diagnostic | 170 / 172 (98 %) | 152 / 172 (88 %) | 170 / 172 (98 %) |
| nuit2 + correctifs | base2 | base | diagnostic | 160 / 160 (100 %) | 144 / 160 (90 %) | 160 / 160 (100 %) |
| nuit2 + correctifs | base3 | base | diagnostic | 161 / 161 (100 %) | 150 / 161 (93 %) | 161 / 161 (100 %) |
| nuit2 + correctifs | base4 | base | diagnostic | 154 / 154 (100 %) | 137 / 154 (88 %) | 154 / 154 (100 %) |
| nuit2 + correctifs | controle1-base | base | controle | 118 / 131 (90 %) | 120 / 131 (91 %) | 121 / 131 (92 %) |
| nuit2 + correctifs | controle1-complexe | complexe | controle | 110 / 165 (66 %) | 148 / 165 (89 %) | 140 / 165 (84 %) |
| nuit3 | lots écrits à la main | base | — | 874 / 874 (100 %) | — | 873 / 874 (99 %) |
| nuit3 | serie1 | complexe | diagnostic | 126 / 187 (67 %) | 162 / 187 (86 %) | 155 / 187 (82 %) |
| nuit3 | base1 | base | diagnostic | 170 / 172 (98 %) | 153 / 172 (88 %) | 171 / 172 (99 %) |
| nuit3 | base2 | base | diagnostic | 160 / 160 (100 %) | 140 / 160 (87 %) | 159 / 160 (99 %) |
| nuit3 | base3 | base | diagnostic | 161 / 161 (100 %) | 144 / 161 (89 %) | 161 / 161 (100 %) |
| nuit3 | base4 | base | diagnostic | 154 / 154 (100 %) | 133 / 154 (86 %) | 154 / 154 (100 %) |
| nuit3 | controle1-base | base | controle | 118 / 131 (90 %) | 118 / 131 (90 %) | 120 / 131 (91 %) |
| nuit3 | controle1-complexe | complexe | controle | 110 / 165 (66 %) | 147 / 165 (89 %) | 138 / 165 (83 %) |
| nuit4 | lots écrits à la main | base | — | 874 / 874 (100 %) | — | 873 / 874 (99 %) |
| nuit4 | serie1 | complexe | diagnostic | 126 / 187 (67 %) | 160 / 187 (85 %) | 154 / 187 (82 %) |
| nuit4 | base1 | base | diagnostic | 170 / 172 (98 %) | 150 / 172 (87 %) | 170 / 172 (98 %) |
| nuit4 | base2 | base | diagnostic | 160 / 160 (100 %) | 145 / 160 (90 %) | 160 / 160 (100 %) |
| nuit4 | base3 | base | diagnostic | 161 / 161 (100 %) | 146 / 161 (90 %) | 161 / 161 (100 %) |
| nuit4 | base4 | base | diagnostic | 154 / 154 (100 %) | 138 / 154 (89 %) | 154 / 154 (100 %) |
| nuit4 | controle1-base | base | controle | 118 / 131 (90 %) | 120 / 131 (91 %) | 121 / 131 (92 %) |
| nuit4 | controle1-complexe | complexe | controle | 110 / 165 (66 %) | 147 / 165 (89 %) | 136 / 165 (82 %) |
| nuit5 | lots écrits à la main | base | — | 874 / 874 (100 %) | — | 874 / 874 (100 %) |
| nuit5 | serie1 | complexe | diagnostic | 127 / 187 (67 %) | 161 / 187 (86 %) | 157 / 187 (83 %) |
| nuit5 | base1 | base | diagnostic | 170 / 172 (98 %) | 152 / 172 (88 %) | 170 / 172 (98 %) |
| nuit5 | base2 | base | diagnostic | 160 / 160 (100 %) | 147 / 160 (91 %) | 160 / 160 (100 %) |
| nuit5 | base3 | base | diagnostic | 161 / 161 (100 %) | 143 / 161 (88 %) | 161 / 161 (100 %) |
| nuit5 | base4 | base | diagnostic | 154 / 154 (100 %) | 137 / 154 (88 %) | 154 / 154 (100 %) |
| nuit5 | controle1-base | base | controle | 118 / 131 (90 %) | 119 / 131 (90 %) | 122 / 131 (93 %) |
| nuit5 | controle1-complexe | complexe | controle | 110 / 165 (66 %) | 152 / 165 (92 %) | 141 / 165 (85 %) |
