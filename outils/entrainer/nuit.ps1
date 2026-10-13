# Toute la chaîne d'entraînement, à lancer le soir (le PC est très occupé pendant 2 à 3 heures) :
# les phrases écrites par le 4B, l'entraînement du 0.6B, puis la conversion en GGUF pour le téléphone.
# Usage (dans PowerShell, depuis le dossier du projet) : powershell -ExecutionPolicy Bypass -File outils\entrainer\nuit.ps1
# Prérequis : les tâches de la fabrique dans $dossier\taches.jsonl (FabriqueEntrainementTest, EVERMIND_FABRIQUE).
$ErrorActionPreference = "Stop"
$env:PYTHONIOENCODING = "utf-8"
$racine = "$env:LOCALAPPDATA\Evermind-entrainement"
$dossier = "$racine\donnees"
$py = "$racine\.venv\Scripts\python.exe"
$convertisseur = Get-ChildItem -Recurse -Filter convert_hf_to_gguf.py "app\.cxx" | Select-Object -First 1 -ExpandProperty FullName
Start-Transcript "$dossier\nuit.log" -Append

if (-not (Test-Path "$dossier\exemples.jsonl")) {
    python outils\entrainer\phrases.py "$dossier\taches.jsonl" "$dossier\exemples.jsonl" 4 8
}
& $py outils\entrainer\entrainer.py "$dossier\exemples.jsonl" "$racine\modeles\Qwen3-0.6B" "$racine\modeles\evermind-0.6b" 2 64
& $py $convertisseur "$racine\modeles\evermind-0.6b" --outtype q8_0 --outfile "$racine\modeles\evermind-0.6b-q8_0.gguf"
Stop-Transcript
