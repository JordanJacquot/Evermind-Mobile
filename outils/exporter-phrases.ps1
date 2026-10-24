# Evermind Mobile : refait le fichier de référence des tests de lecture des phrases
# (app/src/sharedTest/resources/phrases-evermind-pc.json) à partir d'Evermind PC.
# Il contient la réponse de Read-Phrase (comprendre.ps1) pour toutes les phrases de catalogue-phrases.ps1 et de
# tester-phrases.ps1, avec « maintenant » fixé au samedi 10 octobre 2026 à 18 h 30 (comme les catalogues).
# À relancer quand la lecture change sur PC, puis porter le changement en Kotlin jusqu'à ce que les tests passent.
#
#   .\outils\exporter-phrases.ps1 [-Evermind <dossier du dépôt Evermind PC>]
param([string]$Evermind = (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'Evermind'))

$outils = Join-Path $Evermind 'outils'
$comprendre = Join-Path $Evermind 'Evermind\fichiers\modules\comprendre.ps1'
if (-not (Test-Path $comprendre)) { throw "Evermind PC introuvable : $Evermind" }

# Les phrases du catalogue : tout ce qui précède sa partie « Vérification » (sans son param(), son dossier ni son chargement)
$catalogue = [IO.File]::ReadAllLines((Join-Path $outils 'catalogue-phrases.ps1'), [Text.Encoding]::UTF8)
$fin = [array]::FindIndex($catalogue, [Predicate[string]] { param($l) $l -match '^# Vérification' }) - 1
$lignes = @($catalogue[0..($fin - 1)] | Where-Object { $_ -notmatch '^param\(|^\$racine = ' } | ForEach-Object { if ($_ -match '^\. \(Join-Path \$racine') { ". '$comprendre'" } else { $_ } })
# Les phrases de tester-phrases.ps1 : son tableau $cas, renommé
$testeur = [IO.File]::ReadAllLines((Join-Path $outils 'tester-phrases.ps1'), [Text.Encoding]::UTF8)
$debut = [array]::FindIndex($testeur, [Predicate[string]] { param($l) $l -match '^\$cas = @\(' })
$finCas = [array]::FindIndex($testeur, $debut, [Predicate[string]] { param($l) $l -match '^\)\s*$' })
$lignes += '$casTest = @('; $lignes += $testeur[($debut + 1)..$finCas]

$export = {
    $sortie = New-Object System.Collections.ArrayList
    $vus = @{}
    $toutes = @($cas | ForEach-Object { $_.Phrase }) + @($casTest | ForEach-Object { $_[0] })
    $fmt = { param($d) if ($null -eq $d) { $null } else { ([datetime]$d).ToString('yyyy-MM-ddTHH:mm') } }
    foreach ($phrase in $toutes) {
        if ($vus.ContainsKey($phrase)) { continue }; $vus[$phrase] = 1
        $p = Read-Phrase $phrase $now
        $rep = $null
        if ($p.Repetition) {
            $rep = [ordered]@{}
            foreach ($k in $p.Repetition.Keys) { $v = $p.Repetition[$k]; if ($null -ne $v) { $rep[$k] = $v } }
            if ($rep.Contains('Jours')) { $rep['Jours'] = @($rep['Jours'] | ForEach-Object { [int]$_ }) }
            if ($rep.Contains('Sauf')) { $rep['Sauf'] = @($rep['Sauf']) }
        }
        [void]$sortie.Add([ordered]@{
            Phrase = $phrase; Titre = [string]$p.Titre; Manque = [string]$p.Manque; Quand = (& $fmt $p.Quand)
            Jour = $(if ($p.Jour) { ([datetime]$p.Jour).ToString('yyyy-MM-dd') } else { $null })
            Heure = $(if ($null -ne $p.Heure) { ([timespan]$p.Heure).ToString('hh\:mm') } else { $null })
            Type = [string]$p.Type; Journee = [bool]$p.Journee; Fin = (& $fmt $p.Fin); Repetition = $rep
            Lieu = [string]$p.Lieu; Avec = @($p.Avec | Where-Object { $_ }); Lien = [string]$p.Lien; Important = [bool]$p.Important
            Rappels = $(if ($null -ne $p.Rappels) { @($p.Rappels) } else { $null }); Naissance = $p.Naissance
            APreparer = @($p.APreparer | Where-Object { $_ })
            Jours = @($p.Jours | Where-Object { $_ } | ForEach-Object { ([datetime]$_).ToString('yyyy-MM-dd') })
            Blocs = @($p.Blocs | Where-Object { $_ } | ForEach-Object { ([datetime]$_.Debut).ToString('yyyy-MM-dd') + '/' + ([datetime]$_.Fin).ToString('yyyy-MM-dd') })
            Remarque = [string]$p.Remarque
        })
    }
    @{ Maintenant = '2026-10-10T18:30'; Phrases = $sortie }
}

# Le tout dans un même bloc (les fonctions du catalogue et de comprendre.ps1 restent visibles)
$bloc = [scriptblock]::Create(($lignes -join "`n") + "`n" + $export.ToString())
$resultat = & $bloc
$json = ConvertTo-Json -InputObject $resultat -Depth 6 -Compress
$cible = Join-Path (Split-Path $PSScriptRoot -Parent) 'app\src\sharedTest\resources\phrases-evermind-pc.json'
[IO.File]::WriteAllText($cible, $json, (New-Object Text.UTF8Encoding($false)))
Write-Host "Phrases exportées : $($resultat.Phrases.Count) -> $cible"
