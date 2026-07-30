# Verifies that a Sales pull request is clean, pushed, current, and sales/-only.
[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [string]$BaseRef = 'upstream/sales'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $result = @(& git -C $script:ResolvedRepositoryRoot @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed:`n$($result -join "`n")"
    }
    return $result
}

function Invoke-GitSingle {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $lines = @(Invoke-Git -Arguments $Arguments)
    if ($lines.Count -ne 1) {
        throw "Expected one line from git $($Arguments -join ' '), received $($lines.Count)."
    }
    return [string]$lines[0]
}

function Convert-ToCounts {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value,
        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    $parts = @($Value.Trim() -split '\s+')
    if ($parts.Count -ne 2) {
        throw "Cannot parse $Description counts: $Value"
    }
    return @([int]$parts[0], [int]$parts[1])
}

function Assert-SalesPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue
    )

    $normalized = $PathValue.Replace('\', '/')
    if ([string]::IsNullOrWhiteSpace($normalized) -or
        $normalized.StartsWith('/') -or
        $normalized -match '(^|/)\.\.(/|$)' -or
        -not $normalized.StartsWith('sales/', [System.StringComparison]::Ordinal)) {
        throw "Pull request path is outside sales/: $PathValue"
    }
}

function Get-DiffPaths {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Range
    )

    $nameStatus = @(Invoke-Git -Arguments @(
        'diff',
        '--name-status',
        '--find-renames',
        $Range
    ))
    $paths = New-Object System.Collections.Generic.List[string]
    foreach ($line in $nameStatus) {
        $columns = @(([string]$line) -split "`t")
        if ($columns.Count -lt 2) {
            throw "Cannot parse git diff entry: $line"
        }
        for ($index = 1; $index -lt $columns.Count; $index++) {
            Assert-SalesPath -PathValue $columns[$index]
            $paths.Add($columns[$index].Replace('\', '/'))
        }
    }
    return @($paths | Sort-Object -Unique)
}

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}
$script:ResolvedRepositoryRoot = [System.IO.Path]::GetFullPath($RepositoryRoot)

$actualRoot = Invoke-GitSingle -Arguments @(
    'rev-parse',
    '--show-toplevel'
)
$actualRoot = [System.IO.Path]::GetFullPath($actualRoot.Trim())
if (-not $actualRoot.Equals(
    $script:ResolvedRepositoryRoot,
    [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "RepositoryRoot does not match git toplevel: $actualRoot"
}

$dirty = @(Invoke-Git -Arguments @(
    'status',
    '--porcelain=v1',
    '--untracked-files=all'
))
if ($dirty.Count -gt 0) {
    throw "Working tree is not clean:`n$($dirty -join "`n")"
}

$branch = Invoke-GitSingle -Arguments @(
    'symbolic-ref',
    '--quiet',
    '--short',
    'HEAD'
)
$branch = $branch.Trim()
if ([string]::IsNullOrWhiteSpace($branch)) {
    throw 'Detached HEAD is not allowed for a pull request.'
}

$baseCommit = Invoke-GitSingle -Arguments @(
    'rev-parse',
    '--verify',
    "$BaseRef^{commit}"
)
$baseCommit = $baseCommit.Trim()

$null = & git -C $script:ResolvedRepositoryRoot merge-base --is-ancestor $BaseRef HEAD 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "HEAD does not contain the latest $BaseRef. Synchronize before creating the PR."
}

$tracking = Invoke-GitSingle -Arguments @(
    'rev-parse',
    '--abbrev-ref',
    '--symbolic-full-name',
    '@{u}'
)
$tracking = $tracking.Trim()
if (-not $tracking.StartsWith(
    'origin/',
    [System.StringComparison]::Ordinal)) {
    throw "Current branch must track origin, but tracks: $tracking"
}

$trackingCounts = Convert-ToCounts `
    -Value (Invoke-GitSingle -Arguments @(
        'rev-list',
        '--left-right',
        '--count',
        "$tracking...HEAD"
    )) `
    -Description 'tracking branch'
if ($trackingCounts[0] -ne 0 -or $trackingCounts[1] -ne 0) {
    throw "HEAD and $tracking differ (remote-only=$($trackingCounts[0]), local-only=$($trackingCounts[1])). Push or synchronize first."
}

$baseCounts = Convert-ToCounts `
    -Value (Invoke-GitSingle -Arguments @(
        'rev-list',
        '--left-right',
        '--count',
        "$BaseRef...HEAD"
    )) `
    -Description 'base branch'
if ($baseCounts[0] -ne 0) {
    throw "HEAD is behind $BaseRef by $($baseCounts[0]) commit(s)."
}
if ($baseCounts[1] -eq 0) {
    throw "HEAD has no commits to submit relative to $BaseRef."
}

$threeDotSet = @(Get-DiffPaths -Range "$BaseRef...HEAD")
if ($threeDotSet.Count -eq 0) {
    throw "No pull request files found relative to $BaseRef."
}

$twoDotSet = @(Get-DiffPaths -Range "$BaseRef..HEAD")
$pathDifference = @(Compare-Object -ReferenceObject $threeDotSet -DifferenceObject $twoDotSet)
if ($pathDifference.Count -gt 0) {
    throw 'Two-dot and three-dot pull request path sets differ.'
}

$null = Invoke-Git -Arguments @('diff', '--check', "$BaseRef...HEAD")
$headCommit = Invoke-GitSingle -Arguments @('rev-parse', 'HEAD')

Write-Host 'Sales PR scope gate passed.' -ForegroundColor Green
Write-Host "Branch: $branch"
Write-Host "Tracking: $tracking"
Write-Host "Base: $BaseRef ($baseCommit)"
Write-Host "Head: $($headCommit.Trim())"
Write-Host "Commits ahead: $($baseCounts[1])"
Write-Host "Files: $($threeDotSet.Count)"
$threeDotSet | ForEach-Object { Write-Host "  $_" }
