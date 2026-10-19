#!/usr/bin/env bash
# Le paquet de l'assistant pour Evermind PC (docs/ASSISTANT-PC.md) : le programme (assistant-pc) et ses bibliothèques,
# dans un zip à mettre dans une version GitHub « assistant-pc-vN ». Java (Temurin 21) est téléchargée à part par
# Evermind PC, depuis Adoptium.
# Usage (Git Bash) : bash outils/assistant-pc.sh <version>   ->   app/build/evermind-assistant-pc-<version>.zip
set -eu
cd "$(dirname "$0")/.."
V="${1:?version}"
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
./gradlew :assistant-pc:installDist --console=plain -q
D="assistant-pc/build/paquet/evermind-assistant"
rm -rf "assistant-pc/build/paquet"; mkdir -p "$D"
cp -r assistant-pc/build/install/evermind-assistant/lib "$D/lib"
echo "$V" > "$D/version.txt"
Z="$(pwd)/app/build/evermind-assistant-pc-$V.zip"
rm -f "$Z"
# (avec Python : Compress-Archive de Windows PowerShell écrit « evermind-assistant\lib », qu'Evermind PC ne reconnaît pas)
(cd assistant-pc/build/paquet && python -c "import os, sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'w', zipfile.ZIP_DEFLATED) as z:
    for d, _, fs in os.walk('evermind-assistant'):
        for f in fs: z.write(os.path.join(d, f), os.path.join(d, f).replace(os.sep, '/'))" "$(cygpath -w "$Z")")
echo "$Z : $(stat -c %s "$Z") octets, SHA-256 $(sha256sum "$Z" | cut -d' ' -f1 | tr a-f A-F)"
