# Downloads dated Geofabrik state extracts into data/osm, resuming partial files
# and checking each against Geofabrik's MD5 file.
#
#   pwsh tools/fetch_osm_extracts.ps1 -States pennsylvania,ohio -Date 261114
#
# -Date is Geofabrik's YYMMDD stamp. Dated files are kept by Geofabrik for a
# limited time; use a recent date.
param(
    [string[]]$States = @('pennsylvania', 'ohio'),
    [Parameter(Mandatory = $true)][string]$Date
)

$ErrorActionPreference = 'Stop'
# "pwsh -File" passes "a,b" as one string, so split it here.
$States = @($States | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$base ='https://download.geofabrik.de/north-america/us'
$outDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'data/osm'
New-Item -ItemType Directory -Force $outDir | Out-Null

function Get-Resumable([string]$Url, [string]$Path) {
    # curl.exe ships with Windows; -C - resumes a partial file.
    & curl.exe -sSL --fail -C - --retry 30 --retry-delay 5 --retry-all-errors -o $Path $Url
    if ($LASTEXITCODE -ne 0) { throw "download failed ($LASTEXITCODE): $Url" }
}

foreach ($state in $States) {
    $pbf = Join-Path $outDir "$state-$Date.osm.pbf"
    $md5File = "$pbf.md5"
    # Geofabrik keeps each US state in its own folder (north-america/us/<state>/) since November 2026
    $dir = "$base/$state"
    Get-Resumable "$dir/$state-$Date.osm.pbf.md5" $md5File
    Get-Resumable "$dir/$state.poly" (Join-Path $outDir "$state.poly")

    $want = ((Get-Content $md5File -Raw).Trim() -split '\s+')[0].ToLower()
    for ($try = 1; $try -le 3; $try++) {
        Get-Resumable "$dir/$state-$Date.osm.pbf" $pbf
        $have = (Get-FileHash -Algorithm MD5 $pbf).Hash.ToLower()
        if ($have -eq $want) { break }
        Write-Output "${state}: MD5 mismatch on try $try, downloading again from scratch"
        Remove-Item $pbf -Force
    }
    if ($have -ne $want) { throw "${state}: MD5 still wrong after 3 tries" }
    Write-Output ("{0}: ok, {1:N1} MB, md5 {2}" -f $state, ((Get-Item $pbf).Length / 1MB), $have)
}
Write-Output 'done'
