# Dot-source this script to load the root .env into the current PowerShell process.
$envFile = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'
if (!(Test-Path -LiteralPath $envFile)) { throw 'Missing .env. Copy .env.example and configure it first.' }
Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $configName = $Matches[1]
        $configValue = $Matches[2].Trim()
        if ($configValue.Length -ge 2 -and (
            ($configValue.StartsWith("'") -and $configValue.EndsWith("'")) -or
            ($configValue.StartsWith('"') -and $configValue.EndsWith('"')))) {
            $configValue = $configValue.Substring(1, $configValue.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($configName, $configValue, 'Process')
    }
}
