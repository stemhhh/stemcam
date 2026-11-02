# Best-effort WinUSB INF install for AOA accessory (run elevated).
# Prefer the Inno Setup installer which invokes this after copying files.
$ErrorActionPreference = "Stop"
$Inf = Join-Path $PSScriptRoot "opcm_accessory.inf"
if (-not (Test-Path $Inf)) { throw "INF not found: $Inf" }

Write-Host "Installing $Inf (requires Administrator)…"
pnputil /add-driver "$Inf" /install
Write-Host "Done. Unplug/replug the phone after OBS switches it to accessory mode."
