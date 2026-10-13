# Refait app/src/main/assets/lexique/communes.txt : toutes les communes de France (geo.api.gouv.fr, données publiques),
# « nom;population;prononciation », la plus peuplée d'abord. La prononciation n'est écrite que pour les villes qui ne se
# disent pas comme elles s'écrivent (Metz « messe », Reims « rince »...).
# Usage : python outils/lexique-communes.py
import json
import os
import urllib.request

PRONONCIATIONS = {
    "Metz": "messe|mets", "Reims": "rince", "Auxerre": "ausserre|ausser", "Bruxelles": "brusselle", "Woippy": "ouapi", "Forbach": "forbak",
    "Laon": "lan", "Caen": "can", "Sens": "sance", "Auch": "oche", "Guise": "guyse", "Craon": "cran", "Saint-Gaudens": "sin godince",
    "Riom": "riome", "Ussel": "ussèle", "Mâcon": "makon", "Montbéliard": "monbéliar", "Lens": "lance", "Dax": "dakse",
    "Pau": "po", "Aix-en-Provence": "èksanprovance", "Aix-les-Bains": "èkslébin", "Saint-Ouen-sur-Seine": "sintouinsurséne",
    "Bourg-en-Bresse": "bourkanbrèsse", "Thionville": "tionvile", "Hayange": "ayange", "Sarreguemines": "sarguemine|sargemin",
    "Saint-Avold": "sintavol", "Creutzwald": "kreutsvalde", "Freyming-Merlebach": "frèmingmèrlebak", "Uckange": "ukange",
    "Yutz": "yutse", "Hagondange": "agondange", "Florange": "florange", "Fameck": "famèk", "Talange": "talange",
    "Strasbourg": "strasbour", "Colmar": "kolmar", "Mulhouse": "mulouze", "Haguenau": "aguno", "Sélestat": "sélesta",
    "Saint-Dié-des-Vosges": "sindiédévoje", "Saint-Étienne": "sintétiène", "Saint-Denis": "sindeni", "Clermont-Ferrand": "klèrmonféran",
    "Besançon": "bezanson", "Doubs": "dou", "Vesoul": "vezoul", "Montluçon": "monlusson", "Lons-le-Saunier": "lonlesonié",
}

url = "https://geo.api.gouv.fr/communes?fields=nom,population&format=json"
communes = json.load(urllib.request.urlopen(url, timeout=120))
communes.sort(key=lambda c: -(c.get("population") or 0))
vus = set()
lignes = []
for c in communes:
    nom = c["nom"]
    if nom in vus:
        continue
    vus.add(nom)
    lignes.append(f"{nom};{c.get('population') or 0};{PRONONCIATIONS.get(nom, '')}".rstrip(";"))
dest = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "lexique", "communes.txt")
with open(dest, "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(lignes) + "\n")
print(len(lignes), "communes")

# Les 20 000 mots les plus courants du français (FrequencyWords, OpenSubtitles 2018, CC BY-SA 4.0) : jamais pris pour
# une petite commune (« la gare », « sur place »)
url = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/fr/fr_50k.txt"
mots = [l.split(" ")[0] for l in urllib.request.urlopen(url, timeout=120).read().decode("utf-8").splitlines()[:20000]]
dest = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "lexique", "mots.txt")
with open(dest, "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(mots) + "\n")
print(len(mots), "mots courants")
