# Fait écrire par une IA (Qwen3 4B, le llama-server d'Evermind PC) des façons variées de demander des choses à Evermind,
# pour tester l'assistant avec des phrases qu'aucun humain du projet n'a écrites. Chaque intention a un identifiant ;
# la vérification de ce qui doit se passer est dans CatalogueAssistantTest (intentionsGenerees).
# Usage : python outils/generer-phrases.py <sortie.jsonl> [graine] [nombre par intention]
import datetime
import json
import os
import subprocess
import sys
import time
import urllib.request

sortie = sys.argv[1]
graine = int(sys.argv[2]) if len(sys.argv) > 2 else 1
par = int(sys.argv[3]) if len(sys.argv) > 3 else 5

JOURS = ["lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"]
auj = datetime.date.today()
def j(n):
    d = auj + datetime.timedelta(days=n)
    return f"{JOURS[d.weekday()]} {d.day}"

AGENDA = f"""Mon agenda contient :
- Dentiste : {j(3)} de 15 h à 15 h 30, au 12 rue des Lilas à Lyon, avec Maman, important ; notes : code de la porte 4521B ; à prendre : carte vitale, ordonnance
- Appeler maman : aujourd'hui dans deux heures
- Raid avec les potes : tous les {JOURS[(auj + datetime.timedelta(days=1)).weekday()]}s à 21 h 30 (le prochain demain)
- Payer le loyer : le {(auj + datetime.timedelta(days=5)).day} de chaque mois à 10 h
- Anniversaire de Léa : demain, elle aura 24 ans
- Soirée chez Max : {j(6)} à 21 h, avec Max, Inès et Tom
- Foot : {j(4)} à 19 h
- Coiffeur : il y a deux jours (passé)"""

INTENTIONS = {
    "c1": "créer un NOUVEAU rendez-vous chez le dentiste vendredi à 10 h 30 (un autre que celui qui existe)",
    "c2": "créer un dîner chez Paul samedi à 20 h avec Julie",
    "c3": "créer un cours de guitare tous les mercredis à 18 h",
    "c4": "créer un rappel pour payer la facture d'électricité le 10 de chaque mois",
    "c5": "créer l'anniversaire de Thomas le 3 mars, il aura 40 ans",
    "c6": "créer des courses samedi à 10 h avec la liste : lait, pain, œufs",
    "c7": "créer une réunion lundi de 9 h à 11 h à Metz",
    "c8": "créer des vacances du 20 au 27 décembre",
    "c9": "créer un rendez-vous chez l'ophtalmologue le 5 novembre à 14 h, et dire que c'est important",
    "c10": "créer un footing demain à 7 h du matin",
    "m1": "déplacer le foot à dimanche, à la même heure",
    "m2": "décaler le dentiste d'une heure plus tard",
    "m3": "mettre le dentiste à 17 h, le même jour",
    "m4": "dire que le foot a lieu au stade de Woippy",
    "m5": "ajouter Paul à la soirée chez Max",
    "m6": "retirer Tom de la soirée chez Max (les autres restent)",
    "m7": "ajouter ses radios aux choses à prendre pour le dentiste",
    "m8": "marquer le foot comme important",
    "m9": "demander un rappel la veille pour la soirée chez Max",
    "m10": "renommer le foot en « match de foot »",
    "m11": "le raid se fera désormais le jeudi (toutes les semaines)",
    "m12": "le loyer est désormais à payer le 5 de chaque mois",
    "m13": "dire que le dentiste dure une heure",
    "m14": "avancer la soirée chez Max de 30 minutes",
    "m15": "ajouter une note au dentiste : demander un détartrage",
    "s1": "supprimer le foot",
    "s2": "supprimer le coiffeur (qui est passé)",
    "s3": "supprimer seulement le raid de demain (les autres semaines restent)",
    "s4": "supprimer tous les raids du mois de novembre",
    "s5": "arrêter le raid après décembre (plus de raid à partir de janvier)",
    "s6": "supprimer tout ce qui est prévu demain",
    "s7": "annuler la soirée chez Max (elle n'a plus lieu)",
    "f1": "dire que le loyer est payé",
    "f2": "dire que maman a été appelée",
    "q1": "demander ce qui est prévu demain",
    "q2": "demander quand est le dentiste",
    "q3": "demander l'adresse du dentiste",
    "q4": "demander qui vient à la soirée chez Max",
    "q5": "demander le code de la porte du dentiste",
    "q6": "demander ce qu'il faut apporter chez le dentiste",
    "q7": "demander quel âge aura Léa",
    "q8": "demander quel est le prochain rendez-vous",
    "q9": "demander s'il est libre samedi soir",
    "n1": "raconter que le foot était bien la semaine dernière (sans rien demander)",
    "n2": "hésiter à supprimer le dentiste, sans le demander vraiment",
    "n3": "saluer l'assistant et le remercier (sans rien demander)",
}

CONSIGNE = """Tu joues des utilisateurs français d'un agenda qu'on commande à la voix ou à l'écrit, nommé Evermind.
{agenda}

Écris {n} façons TRÈS différentes qu'un utilisateur pourrait employer pour : {intention}.
Varie les gens et les styles : familier, poli, pressé, âgé, jeune, phrase longue avec une raison, phrase très courte,
hésitations de l'oral (« euh », « du coup »), sans ponctuation comme une dictée, une petite faute de frappe parfois.
Chaque phrase doit demander exactement cela, rien d'autre, et rester claire pour un humain. Les dates et heures doivent
être les mêmes. Réponds seulement en JSON : {{"phrases": ["...", "..."]}}"""

serveur = os.path.expandvars(r"%LOCALAPPDATA%\Evermind\voix\ia\llama-server.exe")
modele = os.path.expandvars(r"%LOCALAPPDATA%\Evermind\voix\ia\Qwen3-4B-Instruct-2507-Q4_K_M.gguf")
port = 8099
p = subprocess.Popen([serveur, "-m", modele, "--host", "127.0.0.1", "--port", str(port), "-ngl", "99", "-c", "4096", "--no-webui"],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    for _ in range(240):
        try:
            if urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2).status == 200:
                break
        except Exception:
            time.sleep(0.5)
    schema = {"type": "object", "properties": {"phrases": {"type": "array", "items": {"type": "string"}}}, "required": ["phrases"]}
    lignes = []
    for ident, intention in INTENTIONS.items():
        corps = {
            "messages": [{"role": "user", "content": CONSIGNE.format(agenda=AGENDA, n=par, intention=intention)}],
            "temperature": 1.0, "top_p": 0.95, "seed": graine * 1000 + len(lignes), "max_tokens": 900,
            "response_format": {"type": "json_schema", "json_schema": {"name": "r", "schema": schema}},
        }
        req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions", json.dumps(corps).encode("utf-8"), {"Content-Type": "application/json"})
        rep = json.load(urllib.request.urlopen(req, timeout=300))
        phrases = json.loads(rep["choices"][0]["message"]["content"])["phrases"]
        for ph in phrases[:par]:
            lignes.append({"id": ident, "phrase": ph.strip()})
        print(ident, len(phrases), file=sys.stderr)
    with open(sortie, "w", encoding="utf-8", newline="\n") as f:
        for l in lignes:
            f.write(json.dumps(l, ensure_ascii=False) + "\n")
    print(len(lignes), "phrases")
finally:
    p.terminate()
