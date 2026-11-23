# Copies the freshly built mod jar into the mod's own game folder (Game\mods), replacing the old one.
# Never run as a side effect of build.ps1: deploy is a deliberate step.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Get-ChildItem (Join-Path $root 'build\libs') -Filter 'lightborn-*.jar' | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $jar) { throw 'No built jar; run tools\build.ps1 first' }
$game = Join-Path $root 'Game'
$mods = Join-Path $game 'mods'
# The mod was Runeborn until 2026-11-18: retire its jar and music pack, and carry the owner's key binds and pack list
# over to the new id. Idempotent; worlds are never touched (README: fresh worlds only).
Get-ChildItem $mods -Filter 'runeborn-*.jar' | Remove-Item
$oldMusic = Join-Path $game 'resourcepacks\Runeborn-Music'
if (Test-Path -LiteralPath $oldMusic) { Remove-Item -LiteralPath $oldMusic -Recurse -Force }
$options = Join-Path $game 'options.txt'
if (Test-Path -LiteralPath $options) {
    $text = [IO.File]::ReadAllText($options)
    $new = $text.Replace('key_key.runeborn.', 'key_key.lightborn.').Replace('"file/Runeborn-Music"', '"file/Lightborn-Music"')
    if ($new -ne $text) { [IO.File]::WriteAllText($options, $new, [Text.UTF8Encoding]::new($false)) }
}
Get-ChildItem $mods -Filter 'lightborn-*.jar' | Remove-Item
Copy-Item $jar.FullName $mods
"Deployed $($jar.Name) -> Game\mods"
