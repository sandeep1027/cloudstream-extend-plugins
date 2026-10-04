# Package the dexed plugin in the current directory as a .cs3.
#
# Run from the build/cs3 directory, which holds manifest.json and dex/. Used by
# plugins/<module>/build_cs3.bat; tools/package_cs3.py does the same thing for
# build_cs3.sh.
#
# Every field the zip format would otherwise take from the filesystem is pinned
# here. A rebuild of unchanged sources then produces identical bytes, so the
# published fileHash only moves when the code actually does, and the publisher can
# tell a real change from a rebuild. These values are what .NET's ZipArchive
# writes by default, which is what keeps a Windows build and a CI build of the same
# sources byte for byte identical.
param(
    [string]$Module = '?'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

$epoch = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
$externalAttributes = [int](0x81A40000)

$manifest = Get-Content manifest.json -Raw | ConvertFrom-Json
$cs3 = ($manifest.name -replace '\s', '') + '.cs3'
$archive = Join-Path (Get-Location) $cs3

$dexes = @(Get-ChildItem dex -Filter *.dex | Sort-Object Name)
if ($dexes.Count -eq 0) {
    throw 'no dex in .\dex, refusing to package a .cs3 with no code in it'
}
if (Test-Path $archive) {
    Remove-Item $archive -Force
}

$sources = @()
foreach ($dex in $dexes) {
    $sources += @{ Name = $dex.Name; Path = $dex.FullName }
}
$sources += @{ Name = 'manifest.json'; Path = (Join-Path (Get-Location) 'manifest.json') }

$zip = [IO.Compression.ZipFile]::Open($archive, 'Create')
try {
    foreach ($source in $sources) {
        $entry = $zip.CreateEntry($source.Name, [IO.Compression.CompressionLevel]::Optimal)
        $entry.LastWriteTime = $epoch
        $entry.ExternalAttributes = $externalAttributes
        $stream = $entry.Open()
        try {
            $bytes = [IO.File]::ReadAllBytes($source.Path)
            $stream.Write($bytes, 0, $bytes.Length)
        } finally {
            $stream.Dispose()
        }
    }
} finally {
    # Leaving the archive open keeps the file locked, and the hash below would
    # fail on it.
    $zip.Dispose()
}

$hash = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLower()
Write-Output ('  dex: ' + ($dexes.Name -join ', '))
Write-Output ("  cs3: plugins/$Module/build/cs3/$cs3")
Write-Output ('  "fileSize": "' + (Get-Item $archive).Length + '",')
Write-Output ('  "fileHash": "sha256-' + $hash + '"')