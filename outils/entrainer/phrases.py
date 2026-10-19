# Fait écrire par le Qwen3 4B (le llama-server d'Evermind PC) les phrases de l'utilisateur pour les exemples de la
# fabrique (FabriqueEntrainementTest) : pour chaque intention, quelques façons de la dire. Une phrase n'est gardée que si
# elle contient les indices (le rendez-vous, le jour, l'heure...). Sortie : les exemples d'entraînement, au format
# conversation (consigne courte + agenda, phrase, bonne réponse JSON).
# Usage : python outils/entrainer/phrases.py <taches.jsonl> <sortie.jsonl> [phrases par tâche] [requêtes en parallèle]
import concurrent.futures
import json
import os
import random
import re
import subprocess
import sys
import time
import unicodedata
import urllib.request

taches_f, sortie = sys.argv[1], sys.argv[2]
par = int(sys.argv[3]) if len(sys.argv) > 3 else 4
paralleles = int(sys.argv[4]) if len(sys.argv) > 4 else 6

NOMBRES = {"1": "une", "2": "deux", "3": "trois", "4": "quatre", "5": "cinq", "6": "six", "7": "sept", "8": "huit", "9": "neuf",
           "10": "dix", "11": "onze", "12": "douze|midi", "13": "treize", "14": "quatorze", "15": "quinze", "16": "seize",
           "17": "dix-sept", "18": "dix-huit", "19": "dix-neuf", "20": "vingt", "21": "vingt et un", "22": "vingt-deux",
           "23": "vingt-trois", "24": "vingt-quatre", "25": "vingt-cinq", "28": "vingt-huit", "30": "trente", "0": "minuit"}

def net(s):
    s = unicodedata.normalize("NFD", s.lower())
    return "".join(c for c in s if unicodedata.category(c) != "Mn").replace("œ", "oe")

def contient(ph, indice):
    p, i = net(ph), net(indice)
    if "|" in i:
        # (plusieurs façons de le dire : l'une d'elles suffit)
        return re.search(i, p) is not None
    if i.isdigit():
        if re.search(rf"(?<!\d){i}(?!\d)", p):
            return True
        mots = NOMBRES.get(i)
        return bool(mots) and any(m in p for m in mots.split("|"))
    # (« course » dans « courses », « resto » dans « restaurant »... : le début du mot suffit)
    return i[: max(4, len(i) - 2)] in p

CONSIGNE = """Tu joues un utilisateur français d'Evermind, un agenda qu'on commande à la voix ou à l'écrit.
Nous sommes le {maintenant}. Son agenda :
{agenda}

Écris {n} phrases qu'il pourrait dire à Evermind pour : {intention}.
Une phrase par style, dans cet ordre :
{styles}
Il parle comme un vrai utilisateur : il nomme le rendez-vous comme on le fait à l'oral (« le dentiste », « mon resto avec
Julie », « le raid »), il dit les jours comme un humain (« demain », « jeudi », « le 12 »), sans répéter toute la date
s'il n'en a pas besoin. Chaque phrase demande exactement cela, rien d'autre : on doit comprendre sans hésiter quel
rendez-vous et quel changement (ou quelle question). Elle ne dit rien de faux sur l'agenda.
Réponds seulement en JSON : {{"phrases": ["...", "..."]}}"""

STYLES = ["un ordre direct et court", "poli (« tu peux », « s'il te plaît »)", "familier et jeune", "une personne âgée, un peu formelle",
          "pressé, en style télégraphique", "avec une raison (« parce que... »)", "à l'oral avec une hésitation (« euh », « du coup », « bon »)",
          "une dictée vocale : sans aucune ponctuation", "une phrase longue qui raconte un peu le contexte", "une question (« tu peux... ? », « est-ce que... ? »)",
          "avec une petite faute de frappe", "en commençant par le rendez-vous (« le dentiste, ... »)", "avec « j'aimerais » ou « je voudrais »",
          "à l'impératif (« mets », « enlève », « dis-moi »)"]

JUGE = """Nous sommes le {maintenant}. L'agenda d'un utilisateur :
{agenda}

Voici des phrases qu'il dit à son assistant. Pour chacune, réponds vrai seulement si un humain comprendrait sans hésiter
qu'il demande exactement : {intention}. Faux si la phrase est ambiguë, parle d'un autre rendez-vous, demande autre chose
en plus, demande moins, change d'autres détails, ou n'est pas vraiment une demande.
{liste}
Réponds seulement en JSON : {{"ok": [true, false, ...]}} (une valeur par phrase, dans l'ordre)."""

# Le dossier de l'IA d'Evermind PC : EVERMIND_IA, sinon son emplacement d'origine, sinon celui du dossier Mindset
ia = next((d for d in [os.environ.get("EVERMIND_IA", ""), os.path.expandvars(r"%LOCALAPPDATA%\Evermind\voix\ia"), r"D:\Mindset\Donnees\Evermind\voix\ia"]
           if d and os.path.exists(os.path.join(d, "llama-server.exe"))), None)
if ia is None:
    sys.exit("llama-server.exe d'Evermind PC introuvable : donne son dossier dans EVERMIND_IA")
serveur = os.path.join(ia, "llama-server.exe")
# (le 4B est gardé avec les modèles entraînés : Evermind PC ne s'en sert plus et l'efface de son dossier)
modele = next((m for m in [os.path.expandvars(r"%LOCALAPPDATA%\Evermind-entrainement\modeles\Qwen3-4B-Instruct-2507-Q4_K_M.gguf"),
                           os.path.join(ia, "Qwen3-4B-Instruct-2507-Q4_K_M.gguf")] if os.path.exists(m)), None) or sys.exit("Qwen3 4B introuvable")
port = 8098
schema = {"type": "object", "properties": {"phrases": {"type": "array", "items": {"type": "string"}}}, "required": ["phrases"]}
schema_juge = {"type": "object", "properties": {"ok": {"type": "array", "items": {"type": "boolean"}}}, "required": ["ok"]}

def ia(contenu, sch, graine, temperature):
    corps = {
        "messages": [{"role": "user", "content": contenu}], "temperature": temperature, "top_p": 0.95, "seed": graine, "max_tokens": 700,
        "response_format": {"type": "json_schema", "json_schema": {"name": "r", "schema": sch}},
    }
    req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions", json.dumps(corps).encode("utf-8"), {"Content-Type": "application/json"})
    return json.loads(json.load(urllib.request.urlopen(req, timeout=600))["choices"][0]["message"]["content"])

def demander(t, graine):
    styles = random.Random(graine).sample(STYLES, par)
    phrases = ia(CONSIGNE.format(maintenant=t["maintenant"], agenda=t["agenda"], n=par, intention=t["intention"],
                                 styles="\n".join(f"{i + 1}. {s}" for i, s in enumerate(styles))), schema, graine, 1.0)["phrases"][:par]
    phrases = [" ".join(ph.split()) for ph in phrases]
    candidates = [ph for ph in phrases if ph and all(contient(ph, i) for i in t["indices"])]
    if not candidates:
        return [], len(phrases), 0
    liste = "\n".join(f"{i + 1}. {ph}" for i, ph in enumerate(candidates))
    ok = ia(JUGE.format(maintenant=t["maintenant"], agenda=t["agenda"], intention=t["intention"], liste=liste), schema_juge, graine, 0.0)["ok"]
    bonnes = [ph for ph, v in zip(candidates, ok) if v]
    return bonnes, len(phrases) - len(candidates), len(candidates) - len(bonnes)

def dictee(ph):
    # (comme la dictée du téléphone : pas de ponctuation, parfois tout en minuscules)
    ph = re.sub(r"[,;:!?.]", "", ph)
    return ph.lower() if random.random() < 0.5 else ph

taches = [json.loads(l) for l in open(taches_f, encoding="utf-8")]
p = subprocess.Popen([serveur, "-m", modele, "--host", "127.0.0.1", "--port", str(port), "-ngl", "99", "-c", str(3072 * paralleles),
                      "-np", str(paralleles), "--no-webui"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    for _ in range(240):
        try:
            if urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2).status == 200:
                break
        except Exception:
            time.sleep(0.5)
    random.seed(1)
    gardees = ecartees = jugees = 0
    debut = time.time()
    with open(sortie, "w", encoding="utf-8", newline="\n") as f, concurrent.futures.ThreadPoolExecutor(paralleles) as ex:
        futurs = {ex.submit(demander, t, 1000 + i): t for i, t in enumerate(taches)}
        for k, fu in enumerate(concurrent.futures.as_completed(futurs)):
            t = futurs[fu]
            try:
                phrases, sans_indices, refusees = fu.result()
            except Exception as e:
                print("erreur", e, file=sys.stderr)
                continue
            ecartees += sans_indices
            jugees += refusees
            for ph in phrases:
                if random.random() < 0.15:
                    ph = dictee(ph)
                f.write(json.dumps({"type": t["type"], "messages": [
                    {"role": "system", "content": t["systeme"]},
                    {"role": "user", "content": ph},
                    {"role": "assistant", "content": t["cible"]},
                ]}, ensure_ascii=False) + "\n")
                gardees += 1
            if k % 100 == 0:
                print(f"{k}/{len(taches)} tâches, {gardees} phrases gardées, {ecartees} sans les indices, {jugees} refusées par le juge, {time.time() - debut:.0f} s", file=sys.stderr, flush=True)
    print(f"{gardees} phrases gardées, {ecartees} sans les indices, {jugees} refusées par le juge")
finally:
    p.terminate()
