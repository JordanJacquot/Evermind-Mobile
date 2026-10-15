# Compare Evermind PC à Evermind Mobile : une série de phrases écrites par une IA (outils\phrases-generees) passée à
# l'assistant d'Evermind PC (son vrai chemin : consignes, Qwen3 4B, contrôles, actions), avec les mêmes vérifications
# que CatalogueAssistantTest.intentionsGenerees (à garder pareilles si on y change une intention). « Maintenant » est
# faussé au jour où la série a été écrite (l'agenda d'exemple et les phrases en dépendent).
#
#   .\outils\comparer-pc.ps1 -Serie base1 -Maintenant 2026-10-14T14:00
#   .\outils\comparer-pc.ps1                     toutes les séries de phrases-generees\series.txt, sauf les finales
#
# Il faut le dépôt d'Evermind PC (-EvermindPc) et sa voix installée (-Voix, dossier contenant ia\). Résultat :
# app\build\mesures\pc-<série>.txt (échecs, puis « Phrases générées (PC) : bons / total »). La même série côté
# téléphone : outils/mesurer.sh.
param([string]$Serie, [string]$Maintenant, [string]$EvermindPc = 'D:\Projets\Evermind', [string]$Voix = 'D:\Mindset\Donnees\Evermind\voix')
$ErrorActionPreference = 'Stop'
$depot = Split-Path $PSScriptRoot -Parent
if (-not $Serie) {
    # Les finales ne sont jamais regardées (docs/PLAN-1.0.md) : pas de détail d'échecs pour elles, donc pas ici
    foreach ($l in [IO.File]::ReadAllLines((Join-Path $PSScriptRoot 'phrases-generees\series.txt'), [Text.Encoding]::UTF8)) {
        $c = -split $l
        if (-not $c -or $c[0].StartsWith('#') -or $c.Count -lt 4 -or $c[2] -eq 'finale') { continue }
        powershell -NoProfile -ExecutionPolicy Bypass -File $PSCommandPath -Serie $c[0] -Maintenant $c[3] -EvermindPc $EvermindPc -Voix $Voix
    }
    return
}
if (-not $Maintenant) { throw 'Donne -Maintenant : le jour et l''heure où la série a été écrite (2026-10-14T14:00).' }
$dossierSeries = Join-Path $PSScriptRoot 'phrases-generees'
$SerieFichier = Join-Path $dossierSeries "$Serie.jsonl"
$Ecartees = Join-Path $dossierSeries "$Serie-ecartees.txt"
New-Item -ItemType Directory -Force (Join-Path $depot 'app\build\mesures') | Out-Null
$Sortie = Join-Path $depot "app\build\mesures\pc-$Serie.txt"
$racine = $EvermindPc
# « Maintenant » faussé pour tout Evermind (les modules appellent Get-Date)
$global:EmDecalage = [datetime]$Maintenant - (Microsoft.PowerShell.Utility\Get-Date)
function global:Get-Date {
    [CmdletBinding()] param([Parameter(Position = 0, ValueFromPipeline = $true)][datetime]$Date, [string]$Format, [string]$UFormat,
        [int]$Year, [int]$Month, [int]$Day, [int]$Hour, [int]$Minute, [int]$Second, [int]$Millisecond)
    process {
        $p = @{} + $PSBoundParameters
        if (-not $p.ContainsKey('Date')) { $p['Date'] = (Microsoft.PowerShell.Utility\Get-Date) + $global:EmDecalage }
        Microsoft.PowerShell.Utility\Get-Date @p
    }
}

# Agenda d'exemple refait pour ce « maintenant »
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $racine 'outils\tester.ps1'), [ref]$null, [ref]$null)
$fn = $ast.Find({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'New-DonneesExemple' }, $true)
$utf8 = New-Object Text.UTF8Encoding($false)
. ([scriptblock]::Create($fn.Extent.Text))
New-DonneesExemple (Join-Path $racine '_test\donnees-agenda')

# Le haut du catalogue de l'assistant (préparation, serveur de l'IA, Invoke-Demande, aides), puis ses cas remplacés
$src = [IO.File]::ReadAllText((Join-Path $racine 'outils\catalogue-assistant.ps1'), [Text.Encoding]::UTF8)
$haut = $src.Substring(0, $src.IndexOf("`n# Famille, phrases (une conversation)"))
$haut = $haut -replace '(?m)^param\(.*$', '$Filtre = '''''
$haut = $haut.Replace('$PSScriptRoot', "'$(Join-Path $racine 'outils')'")
. ([scriptblock]::Create($haut))

function Get-Rep($r) { if ($r) { $r.Repetition } }
function Get-Avec($r) { @($r.Avec | Where-Object { $_ }) }
$JEU_ = 4
$v = @{
    c1 = { @(Get-RV '*entiste*' | Where-Object { Test-Jour $_ (Get-Prochain 5) '10:30' }).Count -ge 1 -and @(Get-RV 'Dentiste' | Where-Object { Test-Jour $_ $dentiste '15:00' }).Count -ge 1 }
    c2 = { $r = @($script:Agenda | Where-Object { ([datetime]$_.Quand).DayOfWeek -eq 'Saturday' -and ([datetime]$_.Quand).Hour -eq 20 })[0]; $r -and @(Get-Avec $r | Where-Object { $_ -eq 'Julie' }).Count }
    c3 = { $r = @($script:Agenda | Where-Object { $_.Titre -like '*uitare*' })[0]; $r -and [int](Get-Rep $r).Jour -eq 3 -and ([datetime]$r.Quand).Hour -eq 18 }
    c4 = { $r = @($script:Agenda | Where-Object { $_.Titre -like '*lectricit*' -or $_.Titre -like '*facture*' })[0]; $r -and (Get-Rep $r).Type -eq 'mois' -and [int](Get-Rep $r).Jour -eq 10 }
    c5 = { $r = @($script:Agenda | Where-Object { $_.Titre -clike '*Thomas*' })[0]; $r -and $r.Type -eq 'anniv' -and [int]$r.Naissance -eq ((Get-Le 3 3).Year - 40) }
    c6 = { $r = @($script:Agenda | Where-Object { $_.Titre -like '*ourse*' })[0]; $r -and ([datetime]$r.Quand).DayOfWeek -eq 'Saturday' -and ([datetime]$r.Quand).Hour -eq 10 -and @($r.APreparer).Count -eq 3 }
    c7 = { $r = @($script:Agenda | Where-Object { $_.Titre -like '*union*' })[0]; (Test-Jour $r (Get-Prochain 1) '09:00') -and $r.Fin -and ([datetime]$r.Fin).Hour -eq 11 -and [string]$r.Lieu -like '*Metz*' }
    c8 = { $r = @($script:Agenda | Where-Object { $_.Titre -like '*acances*' })[0]; $r -and ([datetime]$r.Quand).Day -eq 20 -and $r.Fin -and ([datetime]$r.Fin).Day -eq 27 }
    c9 = { $r = @($script:Agenda | Where-Object { $_.Titre -like '*phtalmo*' })[0]; (Test-Jour $r (Get-Le 5 11) '14:00') -and $r.Important }
    c10 = { @($script:Agenda | Where-Object { $_.Titre -like '*ooting*' -and (Test-Jour $_ $auj.AddDays(1) '07:00') }).Count -ge 1 }
    m1 = { (Test-Jour (Get-RV 'Foot')[0] (Get-Prochain 0) '19:00') -and @($script:Agenda).Count -eq 8 }
    m2 = { Test-Jour (Get-RV 'Dentiste')[0] $dentiste '16:00' }
    m3 = { Test-Jour (Get-RV 'Dentiste')[0] $dentiste '17:00' }
    m4 = { [string](Get-RV 'Foot')[0].Lieu -like '*Woippy*' }
    m5 = { (Get-Avec (Get-RV 'Soirée chez Max')[0]) -contains 'Paul' }
    m6 = { $a = Get-Avec (Get-RV 'Soirée chez Max')[0]; $a -notcontains 'Tom' -and $a -contains 'Max' -and $a -contains 'Inès' }
    m7 = { @((Get-RV 'Dentiste')[0].APreparer | Where-Object { $_.Texte -like '*radio*' }).Count -ge 1 }
    m8 = { [bool](Get-RV 'Foot')[0].Important }
    m9 = { @((Get-RV 'Soirée chez Max')[0].Rappels) -contains 'veille' }
    m10 = { @($script:Agenda | Where-Object { $_.Titre -eq 'Match de foot' }).Count -ge 1 -and -not (Get-RV 'Foot') }
    m11 = { [int](Get-Rep (Get-RV 'Raid avec les potes')[0]).Jour -eq 4 }
    m12 = { [int](Get-Rep (Get-RV 'Payer le loyer')[0]).Jour -eq 5 }
    m13 = { $r = (Get-RV 'Dentiste')[0]; $r.Fin -and ([datetime]$r.Fin).TimeOfDay -eq [timespan]'16:00' -and ([datetime]$r.Quand).Hour -eq 15 }
    m14 = { Test-Jour (Get-RV 'Soirée chez Max')[0] $soiree '20:30' }
    m15 = { [string](Get-RV 'Dentiste')[0].Notes -like '*tartrage*' }
    s1 = { -not (Get-RV 'Foot') -and @($script:Agenda).Count -eq 7 }
    s2 = { -not (Get-RV 'Coiffeur') -and @($script:Agenda).Count -eq 7 }
    s3 = { $r = (Get-RV 'Raid avec les potes')[0]; $r -and -not @(Get-AgendaEntre $lea $lea.AddDays(1) | Where-Object { $_.RV.Id -eq $r.Id }).Count -and @(Get-AgendaEntre $lea.AddDays(7) $lea.AddDays(8) | Where-Object { $_.RV.Id -eq $r.Id }).Count }
    s4 = { @((Get-Rep (Get-RV 'Raid avec les potes')[0]).Sauf | Where-Object { $_ -like '*-11-*' }).Count -ge 4 }
    s5 = { [string](Get-Rep (Get-RV 'Raid avec les potes')[0]).Jusqua -like '*-12-31' }
    s6 = { -not @(Get-AgendaEntre $lea $lea.AddDays(1) | Where-Object { $_.RV.Type -ne 'anniv' }).Count -and (Get-RV 'Foot') }
    s7 = { -not (Get-RV 'Soirée chez Max') -and @($script:Agenda).Count -eq 7 }
    f1 = { [bool](Get-RV 'Payer le loyer')[0].Fait }
    f2 = { [bool](Get-RV 'Appeler maman')[0].Fait }
    q1 = { @($script:Agenda).Count -eq 8 -and $script:Dit -match 'Léa|raid' }
    q2 = { @($script:Agenda).Count -eq 8 -and ($script:Dit -match "$($dentiste.Day)" -or $script:Dit -match $dentiste.ToString('dddd', $Fr)) }
    q3 = { @($script:Agenda).Count -eq 8 -and $script:Dit -cmatch 'Lilas' }
    q4 = { @($script:Agenda).Count -eq 8 -and $script:Dit -cmatch 'Inès' }
    q5 = { @($script:Agenda).Count -eq 8 -and $script:Dit -cmatch '4521B' }
    q6 = { @($script:Agenda).Count -eq 8 -and $script:Dit -cmatch 'rdonnance' }
    q7 = { @($script:Agenda).Count -eq 8 -and $script:Dit -match '24' }
    q8 = { @($script:Agenda).Count -eq 8 -and $script:Dit -match 'maman' }
    q9 = { @($script:Agenda).Count -eq 8 -and $script:Dit -cmatch 'Foot' }
    n1 = { @($script:Agenda).Count -eq 8 -and (Test-Jour (Get-RV 'Foot')[0] $foot '19:00') }
    n2 = { @($script:Agenda).Count -eq 8 -and (Get-RV 'Dentiste') }
    n3 = { @($script:Agenda).Count -eq 8 }
}

$exclues = @{}; if (Test-Path -LiteralPath $Ecartees) { foreach ($l in [IO.File]::ReadAllLines($Ecartees, [Text.Encoding]::UTF8)) { $exclues[$l.Trim()] = 1 } }
$lignes = @(); $bons = 0; $total = 0
try {
    while ($true) { try { if ((Invoke-WebRequest "http://127.0.0.1:$port/health" -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) { break } } catch { Start-Sleep -Milliseconds 300 } }
    foreach ($l in [IO.File]::ReadAllLines($SerieFichier, [Text.Encoding]::UTF8)) {
        if (-not $l.Trim()) { continue }
        $o = $l | ConvertFrom-Json
        if ($exclues.ContainsKey($o.phrase.Trim()) -or -not $v.ContainsKey($o.id)) { continue }
        Copy-Item $exemple (Join-Path $env:EVERMIND_DONNEES 'rendez-vous.json') -Force
        Import-Agenda
        $script:Conversation = New-Object System.Collections.ArrayList; $script:Annulation = $null; $script:Dit = ''; $script:Ouvertures.Clear(); $script:ListeDossiers = $null
        $trace = ''
        try { $trace = Invoke-Demande $o.phrase } catch { $trace = "ERREUR $($_.Exception.Message)" }
        $bon = $false
        try { $bon = [bool](& $v[$o.id]) } catch {}
        $total++; if ($bon) { $bons++ } else { $lignes += "ÉCHEC [$($o.id)] $($o.phrase)`n    -> $($script:Dit)`n    IA : $trace" }
    }
} finally { Stop-Process -Id $serveur.Id -Force -ErrorAction SilentlyContinue }
$lignes += "Phrases générées (PC) : $bons / $total"
[IO.File]::WriteAllLines($Sortie, $lignes, $utf8)
$lignes[-1]

# L'agenda d'exemple d'Evermind PC remis à aujourd'hui (sinon son catalogue-assistant.ps1 échouerait)
Remove-Item function:global:Get-Date
New-DonneesExemple (Join-Path $racine '_test\donnees-agenda')
