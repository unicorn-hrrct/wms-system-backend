# Runs the fixed Sales classification suite and optional project regression safely.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackendDirectory,

    [string]$SalesDirectory,

    [Parameter(Mandatory = $true)]
    [string]$PostgresUrl,

    [string]$PostgresUser = 'postgres',

    [System.Security.SecureString]$PostgresPassword,

    [string]$RedisHost = '127.0.0.1',

    [Parameter(Mandatory = $true)]
    [int]$RedisPort,

    [System.Security.SecureString]$RedisPassword,

    [Parameter(Mandatory = $true)]
    [string]$JdkHome,

    [Parameter(Mandatory = $true)]
    [string]$MavenCommand,

    [Parameter(Mandatory = $true)]
    [string]$DestructiveTargetConfirmation,

    [string]$ExpectedBackendHead =
        '5dc682ee958ced73a741954cb514d3b7e6ce6bcb',

    [switch]$IncludeProjectRegression,

    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:ExpectedClassificationTests = @(
    [PSCustomObject]@{
        ShortName = 'SalesWriteIdempotencyHeaderTest'
        ClassName = 'com.example.demo.controller.SalesWriteIdempotencyHeaderTest'
        Tests = 1
    },
    [PSCustomObject]@{
        ShortName = 'AftersaleRefundRequestValidationTest'
        ClassName = 'com.example.demo.dto.AftersaleRefundRequestValidationTest'
        Tests = 3
    },
    [PSCustomObject]@{
        ShortName = 'SalesOverlayMigrationTest'
        ClassName = 'com.example.demo.schema.SalesOverlayMigrationTest'
        Tests = 1
    },
    [PSCustomObject]@{
        ShortName = 'AftersaleServiceContractTest'
        ClassName = 'com.example.demo.service.AftersaleServiceContractTest'
        Tests = 5
    },
    [PSCustomObject]@{
        ShortName = 'CustomerAddressServiceContractTest'
        ClassName = 'com.example.demo.service.CustomerAddressServiceContractTest'
        Tests = 4
    },
    [PSCustomObject]@{
        ShortName = 'RefundServiceContractTest'
        ClassName = 'com.example.demo.service.RefundServiceContractTest'
        Tests = 7
    },
    [PSCustomObject]@{
        ShortName = 'CustomerAddressAftersaleRefundHttpIntegrationTest'
        ClassName = 'com.example.demo.controller.CustomerAddressAftersaleRefundHttpIntegrationTest'
        Tests = 2
    },
    [PSCustomObject]@{
        ShortName = 'SalesIdempotencyPostgresIntegrationTest'
        ClassName = 'com.example.demo.service.SalesIdempotencyPostgresIntegrationTest'
        Tests = 5
    }
)
$script:ExpectedClassificationTotalTests = 28
$script:ExpectedResetTests = @(
    [PSCustomObject]@{
        ShortName = 'SalesOverlayMigrationTest'
        ClassName = 'com.example.demo.schema.SalesOverlayMigrationTest'
        Tests = 1
    }
)
$script:ExpectedResetTotalTests = 1
$script:ExpectedProjectTests = @(
    [PSCustomObject]@{
        ShortName = 'DemoApplicationTests'
        ClassName = 'com.example.demo.DemoApplicationTests'
        Tests = 1
    },
    [PSCustomObject]@{
        ShortName = 'AppDataControllerTests'
        ClassName = 'com.example.demo.controller.AppDataControllerTests'
        Tests = 3
    },
    [PSCustomObject]@{
        ShortName = 'AuthControllerTests'
        ClassName = 'com.example.demo.controller.AuthControllerTests'
        Tests = 7
    },
    [PSCustomObject]@{
        ShortName = 'BusinessApiControllerTests'
        ClassName = 'com.example.demo.controller.BusinessApiControllerTests'
        Tests = 8
    },
    [PSCustomObject]@{
        ShortName = 'SalesWriteIdempotencyHeaderTest'
        ClassName = 'com.example.demo.controller.SalesWriteIdempotencyHeaderTest'
        Tests = 1
    },
    [PSCustomObject]@{
        ShortName = 'UserControllerTests'
        ClassName = 'com.example.demo.controller.UserControllerTests'
        Tests = 6
    },
    [PSCustomObject]@{
        ShortName = 'AftersaleRefundRequestValidationTest'
        ClassName = 'com.example.demo.dto.AftersaleRefundRequestValidationTest'
        Tests = 3
    },
    [PSCustomObject]@{
        ShortName = 'AftersaleServiceContractTest'
        ClassName = 'com.example.demo.service.AftersaleServiceContractTest'
        Tests = 5
    },
    [PSCustomObject]@{
        ShortName = 'CustomerAddressServiceContractTest'
        ClassName = 'com.example.demo.service.CustomerAddressServiceContractTest'
        Tests = 4
    },
    [PSCustomObject]@{
        ShortName = 'RefundServiceContractTest'
        ClassName = 'com.example.demo.service.RefundServiceContractTest'
        Tests = 7
    }
)
$script:ExpectedProjectTotalTests = 45
$script:ExpectedMainFiles = 21
$script:ExpectedTestFiles = 8
$script:ExpectedCompatibilityHash =
    '704f8478bab1b4912b278dbf80ac251db5e3b0a2f670a2c11687fa32380e5c32'
$script:CompatibilityPath =
    'backend/src/test/java/com/example/demo/controller/BusinessApiControllerTests.java'
$script:MigrationPath = 'sales/sql/V001__customer_address_refund.sql'
$script:AllowedStatusPaths = @{}
$script:Secrets = @()

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)]
        [string]$WorkingDirectory,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $result = @(& git -C $WorkingDirectory @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed:`n$($result -join "`n")"
    }
    return $result
}

function Invoke-GitSingle {
    param(
        [Parameter(Mandatory = $true)]
        [string]$WorkingDirectory,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $lines = @(Invoke-Git `
        -WorkingDirectory $WorkingDirectory `
        -Arguments $Arguments)
    if ($lines.Count -ne 1) {
        throw "Expected one line from git $($Arguments -join ' '), received $($lines.Count)."
    }
    return ([string]$lines[0]).Trim()
}

function Resolve-ExistingDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    $item = Get-Item -LiteralPath $PathValue -ErrorAction Stop
    if (-not $item.PSIsContainer) {
        throw "$Description is not a directory: $PathValue"
    }
    if (($item.Attributes -band
            [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Description cannot be a junction or symbolic link: $PathValue"
    }
    return [System.IO.Path]::GetFullPath($item.FullName)
}

function Resolve-ExistingFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        $item = Get-Item -LiteralPath $PathValue -ErrorAction Stop
        if ($item.PSIsContainer) {
            throw "$Description is not a file: $PathValue"
        }
        return [System.IO.Path]::GetFullPath($item.FullName)
    }

    $command = Get-Command $PathValue -CommandType Application `
        -ErrorAction Stop
    return [System.IO.Path]::GetFullPath($command.Source)
}

function Assert-NoReparseChain {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue,

        [Parameter(Mandatory = $true)]
        [string]$Boundary,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    $fullPath = [System.IO.Path]::GetFullPath($PathValue)
    $fullBoundary = [System.IO.Path]::GetFullPath($Boundary)
    if (-not $fullPath.Equals(
            $fullBoundary,
            [System.StringComparison]::OrdinalIgnoreCase) -and
        -not $fullPath.StartsWith(
            $fullBoundary +
                [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description is outside its expected boundary."
    }

    $item = Get-Item -LiteralPath $fullPath -Force
    while ($null -ne $item) {
        if (($item.Attributes -band
                [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Description cannot traverse a junction or symbolic link: $($item.FullName)"
        }
        if ([System.IO.Path]::GetFullPath($item.FullName).Equals(
            $fullBoundary,
            [System.StringComparison]::OrdinalIgnoreCase)) {
            return
        }
        if ($item -is [System.IO.DirectoryInfo]) {
            $item = $item.Parent
        } elseif ($item -is [System.IO.FileInfo]) {
            $item = $item.Directory
        } else {
            throw "$Description contains an unsupported filesystem item."
        }
    }
    throw "$Description did not reach its expected boundary."
}

function Get-NormalizedText {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue
    )

    $text = [System.IO.File]::ReadAllText(
        $PathValue,
        [System.Text.Encoding]::UTF8)
    return $text.Replace("`r`n", "`n").Replace("`r", "`n")
}

function Get-NormalizedSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue
    )

    $text = Get-NormalizedText -PathValue $PathValue
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString(
            $algorithm.ComputeHash($bytes))).Replace(
                '-',
                '').ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }
}

function Assert-LoopbackHost {
    param(
        [Parameter(Mandatory = $true)]
        [string]$HostValue,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    $allowed = @('127.0.0.1', 'localhost', '::1')
    if ($allowed -notcontains $HostValue.ToLowerInvariant()) {
        throw "$Description must use an explicit loopback host."
    }
}

function Assert-PostgresTarget {
    $script:PostgresUrl = $PostgresUrl.Trim()
    if (-not $script:PostgresUrl.StartsWith(
        'jdbc:postgresql://',
        [System.StringComparison]::Ordinal)) {
        throw 'PostgresUrl must start with jdbc:postgresql://.'
    }

    try {
        $uri = [System.Uri]($script:PostgresUrl.Substring(5))
    } catch {
        throw "PostgresUrl is not a valid JDBC URL: $script:PostgresUrl"
    }
    if (-not $uri.IsAbsoluteUri -or
        $uri.Scheme -ne 'postgresql') {
        throw 'PostgresUrl must contain an absolute postgresql URI.'
    }
    if (-not [string]::IsNullOrEmpty($uri.UserInfo) -or
        -not [string]::IsNullOrEmpty($uri.Query) -or
        -not [string]::IsNullOrEmpty($uri.Fragment)) {
        throw 'PostgresUrl cannot contain credentials, query, or fragment.'
    }
    Assert-LoopbackHost `
        -HostValue $uri.Host `
        -Description 'PostgreSQL'
    if ($uri.Port -lt 1 -or $uri.Port -gt 65535) {
        throw 'PostgresUrl must include an explicit port.'
    }
    if ($uri.Port -eq 5432) {
        throw 'PostgreSQL default port 5432 is not allowed.'
    }

    $database = [System.Uri]::UnescapeDataString(
        $uri.AbsolutePath.Trim('/'))
    if ($database.Contains('/') -or
        $database -notmatch '^sales_(verify|validation)_[A-Za-z0-9_]+$') {
        throw 'Database name must match sales_verify_* or sales_validation_*.'
    }

    $expectedConfirmation = "DESTROY:$($script:PostgresUrl)"
    if (-not [string]::Equals(
        $DestructiveTargetConfirmation,
        $expectedConfirmation,
        [System.StringComparison]::Ordinal)) {
        throw "DestructiveTargetConfirmation must exactly equal: $expectedConfirmation"
    }

    $script:PostgresUri = $uri
    $script:PostgresDatabase = $database
}

function Assert-RedisTarget {
    Assert-LoopbackHost `
        -HostValue $RedisHost `
        -Description 'Redis'
    if ($RedisPort -lt 1 -or $RedisPort -gt 65535) {
        throw 'RedisPort must be between 1 and 65535.'
    }
    if ($RedisPort -eq 6379) {
        throw 'Redis default port 6379 is not allowed.'
    }
}

function Add-AllowedStatusPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue
    )

    $normalized = $PathValue.Replace('\', '/')
    $script:AllowedStatusPaths[$normalized] = $true
}

function Assert-MappedTree {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourceSubdirectory,

        [Parameter(Mandatory = $true)]
        [string]$TargetSubdirectory,

        [Parameter(Mandatory = $true)]
        [int]$ExpectedFiles
    )

    $sourceRoot = Join-Path `
        $script:SalesDirectoryResolved `
        $SourceSubdirectory
    $targetRoot = Join-Path `
        $script:BackendDirectoryResolved `
        $TargetSubdirectory
    if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) {
        throw "Missing Sales source directory: $sourceRoot"
    }
    if (-not (Test-Path -LiteralPath $targetRoot -PathType Container)) {
        throw "Missing overlay target directory: $targetRoot"
    }

    $files = @(Get-ChildItem `
        -LiteralPath $sourceRoot `
        -Recurse `
        -File)
    if ($files.Count -ne $ExpectedFiles) {
        throw "Expected $ExpectedFiles files under $SourceSubdirectory, found $($files.Count)."
    }

    foreach ($file in $files) {
        if ($file.Extension -ne '.java') {
            throw "Unexpected non-Java classification source: $($file.FullName)"
        }
        Assert-NoReparseChain `
            -PathValue $file.FullName `
            -Boundary $sourceRoot `
            -Description 'Sales classification source'
        $relative = $file.FullName.Substring(
            $sourceRoot.Length + 1)
        $target = Join-Path $targetRoot $relative
        if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
            throw "Overlay is missing mapped file: $target"
        }
        Assert-NoReparseChain `
            -PathValue $target `
            -Boundary $targetRoot `
            -Description 'Overlay mapped source'
        $sourceText = Get-NormalizedText -PathValue $file.FullName
        $targetText = Get-NormalizedText -PathValue $target
        if (-not [string]::Equals(
            $sourceText,
            $targetText,
            [System.StringComparison]::Ordinal)) {
            throw "Overlay file differs from Sales classification source: $relative"
        }

        $gitPath = 'backend/' +
            $TargetSubdirectory.Replace('\', '/').Trim('/') +
            '/' +
            $relative.Replace('\', '/')
        Add-AllowedStatusPath -PathValue $gitPath
    }
}

function Assert-Overlay {
    if ([string]::IsNullOrWhiteSpace($SalesDirectory)) {
        $SalesDirectory = Split-Path -Parent $PSScriptRoot
    }
    $script:SalesDirectoryResolved = Resolve-ExistingDirectory `
        -PathValue $SalesDirectory `
        -Description 'SalesDirectory'
    $script:BackendDirectoryResolved = Resolve-ExistingDirectory `
        -PathValue $BackendDirectory `
        -Description 'BackendDirectory'

    $backendItem = Get-Item -LiteralPath $script:BackendDirectoryResolved
    if ($backendItem.Name -ne 'backend') {
        throw 'BackendDirectory leaf name must be backend.'
    }
    $overlayRootItem = $backendItem.Parent
    if ($overlayRootItem.Name -notmatch
        '^\.tmp-sales-(integration|verification)-[0-9]{8}([_-].*)?$') {
        throw 'BackendDirectory must be inside a .tmp-sales-integration-* or .tmp-sales-verification-* worktree.'
    }
    if (($overlayRootItem.Attributes -band
            [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'The verification worktree root cannot be a junction or symbolic link.'
    }

    foreach ($required in @(
        'pom.xml',
        'src/main/resources/schema.sql',
        'src/main/resources/data.sql')) {
        $requiredPath = Join-Path `
            $script:BackendDirectoryResolved `
            $required
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            throw "Overlay backend is missing required file: $required"
        }
        Assert-NoReparseChain `
            -PathValue $requiredPath `
            -Boundary $script:BackendDirectoryResolved `
            -Description "Overlay required file $required"
    }

    $script:BackendGitRoot = [System.IO.Path]::GetFullPath(
        (Invoke-GitSingle `
            -WorkingDirectory $script:BackendDirectoryResolved `
            -Arguments @('rev-parse', '--show-toplevel')))
    $salesGitRoot = [System.IO.Path]::GetFullPath(
        (Invoke-GitSingle `
            -WorkingDirectory $script:SalesDirectoryResolved `
            -Arguments @('rev-parse', '--show-toplevel')))
    if (-not $script:BackendGitRoot.Equals(
        [System.IO.Path]::GetFullPath($overlayRootItem.FullName),
        [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'BackendDirectory must be the backend child of its Git worktree root.'
    }
    if ($script:BackendGitRoot.Equals(
        $salesGitRoot,
        [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'The real repository backend cannot be used as the verification overlay.'
    }

    $head = Invoke-GitSingle `
        -WorkingDirectory $script:BackendGitRoot `
        -Arguments @('rev-parse', 'HEAD')
    if (-not [string]::Equals(
        $head,
        $ExpectedBackendHead,
        [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Overlay HEAD must be $ExpectedBackendHead, found $head."
    }

    $null = & git -C $script:BackendGitRoot `
        symbolic-ref --quiet HEAD 2>&1
    if ($LASTEXITCODE -eq 0) {
        throw 'Verification overlay must use detached HEAD.'
    }

    Assert-MappedTree `
        -SourceSubdirectory 'src/main/java' `
        -TargetSubdirectory 'src/main/java' `
        -ExpectedFiles $script:ExpectedMainFiles
    Assert-MappedTree `
        -SourceSubdirectory 'src/test/java' `
        -TargetSubdirectory 'src/test/java' `
        -ExpectedFiles $script:ExpectedTestFiles

    $sourceMigration = Join-Path `
        $script:SalesDirectoryResolved `
        'sql/V001__customer_address_refund.sql'
    $overlayMigration = Join-Path `
        $script:BackendGitRoot `
        $script:MigrationPath
    if (-not (Test-Path -LiteralPath $sourceMigration -PathType Leaf) -or
        -not (Test-Path -LiteralPath $overlayMigration -PathType Leaf)) {
        throw 'Sales V001 migration is missing from the source or overlay.'
    }
    Assert-NoReparseChain `
        -PathValue $sourceMigration `
        -Boundary $script:SalesDirectoryResolved `
        -Description 'Sales migration source'
    Assert-NoReparseChain `
        -PathValue $overlayMigration `
        -Boundary $script:BackendGitRoot `
        -Description 'Overlay migration source'
    if (-not [string]::Equals(
        (Get-NormalizedText -PathValue $sourceMigration),
        (Get-NormalizedText -PathValue $overlayMigration),
        [System.StringComparison]::Ordinal)) {
        throw 'Overlay Sales V001 differs from the classification source.'
    }
    Add-AllowedStatusPath -PathValue $script:MigrationPath

    $compatibilityFile = Join-Path `
        $script:BackendGitRoot `
        $script:CompatibilityPath
    if (-not (Test-Path -LiteralPath $compatibilityFile -PathType Leaf)) {
        throw 'The exact BusinessApiControllerTests compatibility file is missing.'
    }
    Assert-NoReparseChain `
        -PathValue $compatibilityFile `
        -Boundary $script:BackendGitRoot `
        -Description 'Compatibility test source'
    $compatibilityHash = Get-NormalizedSha256 `
        -PathValue $compatibilityFile
    if (-not [string]::Equals(
        $compatibilityHash,
        $script:ExpectedCompatibilityHash,
        [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'BusinessApiControllerTests compatibility content is not the reviewed version.'
    }
    Add-AllowedStatusPath -PathValue $script:CompatibilityPath

    $seenStatus = @{}
    $statusLines = @(Invoke-Git `
        -WorkingDirectory $script:BackendGitRoot `
        -Arguments @(
            'status',
            '--porcelain=v1',
            '--untracked-files=all'
        ))
    foreach ($lineValue in $statusLines) {
        $line = [string]$lineValue
        if ($line.Length -lt 4) {
            throw "Cannot parse overlay Git status entry: $line"
        }
        $statusCode = $line.Substring(0, 2)
        $pathValue = $line.Substring(3).Replace('\', '/')
        if ($pathValue.StartsWith('"') -or
            $pathValue.Contains(' -> ')) {
            throw "Quoted or renamed overlay paths are not allowed: $line"
        }
        if ($statusCode -ne ' M' -and $statusCode -ne '??') {
            throw "Staged, deleted, or otherwise unexpected overlay state: $line"
        }
        if (-not $script:AllowedStatusPaths.ContainsKey($pathValue)) {
            throw "Unexpected file in verification overlay: $line"
        }
        $seenStatus[$pathValue] = $statusCode
    }

    if (-not $seenStatus.ContainsKey($script:CompatibilityPath) -or
        $seenStatus[$script:CompatibilityPath] -ne ' M') {
        throw 'BusinessApiControllerTests must contain only the reviewed worktree compatibility change.'
    }
    if (-not $seenStatus.ContainsKey($script:MigrationPath) -or
        $seenStatus[$script:MigrationPath] -ne '??') {
        throw 'Overlay Sales V001 must be the reviewed untracked sibling migration.'
    }
}

function Assert-Toolchain {
    $script:JdkHomeResolved = Resolve-ExistingDirectory `
        -PathValue $JdkHome `
        -Description 'JdkHome'
    $javaPath = Join-Path `
        $script:JdkHomeResolved `
        'bin/java.exe'
    $releasePath = Join-Path `
        $script:JdkHomeResolved `
        'release'
    if (-not (Test-Path -LiteralPath $javaPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $releasePath -PathType Leaf)) {
        throw 'JdkHome must contain bin/java.exe and release.'
    }
    $releaseText = [System.IO.File]::ReadAllText(
        $releasePath,
        [System.Text.Encoding]::UTF8)
    if ($releaseText -notmatch '(?m)^JAVA_VERSION="21(\.|")') {
        throw 'JdkHome must point to Java 21.'
    }

    $script:MavenCommandResolved = Resolve-ExistingFile `
        -PathValue $MavenCommand `
        -Description 'MavenCommand'

    foreach ($name in @(
        'JAVA_TOOL_OPTIONS',
        'JDK_JAVA_OPTIONS',
        '_JAVA_OPTIONS',
        'MAVEN_OPTS',
        'MAVEN_ARGS')) {
        $value = [System.Environment]::GetEnvironmentVariable(
            $name,
            [System.EnvironmentVariableTarget]::Process)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            throw "$name must be unset for deterministic verification."
        }
    }
}

function Test-TcpListener {
    param(
        [Parameter(Mandatory = $true)]
        [string]$HostValue,

        [Parameter(Mandatory = $true)]
        [int]$PortValue,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $asyncResult = $client.BeginConnect(
            $HostValue,
            $PortValue,
            $null,
            $null)
        if (-not $asyncResult.AsyncWaitHandle.WaitOne(3000)) {
            throw "$Description did not accept a TCP connection within 3 seconds."
        }
        $client.EndConnect($asyncResult)
    } catch {
        throw "$Description is not reachable at ${HostValue}:${PortValue}: $($_.Exception.Message)"
    } finally {
        $client.Dispose()
    }
}

function ConvertFrom-SecureValue {
    param(
        [AllowNull()]
        [System.Security.SecureString]$Value
    )

    if ($null -eq $Value) {
        return ''
    }
    $pointer = [System.IntPtr]::Zero
    try {
        $pointer = [System.Runtime.InteropServices.Marshal]::
            SecureStringToBSTR($Value)
        return [System.Runtime.InteropServices.Marshal]::
            PtrToStringBSTR($pointer)
    } finally {
        if ($pointer -ne [System.IntPtr]::Zero) {
            [System.Runtime.InteropServices.Marshal]::
                ZeroFreeBSTR($pointer)
        }
    }
}

function Protect-Output {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Value
    )

    $protected = $Value
    foreach ($secret in $script:Secrets) {
        if (-not [string]::IsNullOrEmpty($secret)) {
            $protected = $protected.Replace($secret, '<redacted>')
        }
    }
    return $protected
}

function Invoke-Maven {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Description,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    Write-Host "== $Description =="
    Write-Host "mvn $($Arguments -join ' ')"
    $previousLocation = Get-Location
    $previousPreference = $ErrorActionPreference
    try {
        Set-Location -LiteralPath $script:BackendDirectoryResolved
        $ErrorActionPreference = 'Continue'
        & $script:MavenCommandResolved @Arguments 2>&1 |
            ForEach-Object {
                Write-Host (Protect-Output -Value ([string]$_))
            }
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
        Set-Location -LiteralPath $previousLocation
    }
    if ($exitCode -ne 0) {
        throw "$Description failed with exit code $exitCode."
    }
}

function Get-RequiredIntegerAttribute {
    param(
        [Parameter(Mandatory = $true)]
        [System.Xml.XmlElement]$Element,

        [Parameter(Mandatory = $true)]
        [string]$AttributeName,

        [Parameter(Mandatory = $true)]
        [string]$ReportName
    )

    $value = $Element.GetAttribute($AttributeName)
    $parsed = 0
    if ([string]::IsNullOrWhiteSpace($value) -or
        -not [int]::TryParse(
            $value,
            [System.Globalization.NumberStyles]::Integer,
            [System.Globalization.CultureInfo]::InvariantCulture,
            [ref]$parsed)) {
        throw "Invalid $AttributeName in $ReportName."
    }
    return $parsed
}

function Read-SafeXml {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue
    )

    $settings = New-Object System.Xml.XmlReaderSettings
    $settings.DtdProcessing =
        [System.Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = [System.Xml.XmlReader]::Create(
        $PathValue,
        $settings)
    try {
        $document = New-Object System.Xml.XmlDocument
        $document.XmlResolver = $null
        $document.Load($reader)
        return $document
    } finally {
        $reader.Dispose()
    }
}

function Assert-SurefireReports {
    param(
        [Parameter(Mandatory = $true)]
        [System.DateTime]$TestStartedUtc,

        [Parameter(Mandatory = $true)]
        [System.Object[]]$ExpectedTests,

        [Parameter(Mandatory = $true)]
        [int]$ExpectedTotalTests,

        [Parameter(Mandatory = $true)]
        [string]$PhaseName
    )

    $reportDirectory = Join-Path `
        $script:BackendDirectoryResolved `
        'target/surefire-reports'
    if (-not (Test-Path -LiteralPath $reportDirectory -PathType Container)) {
        throw 'Surefire report directory was not generated.'
    }

    $actualReports = @(Get-ChildItem `
        -LiteralPath $reportDirectory `
        -Filter 'TEST-*.xml' `
        -File)
    $expectedReportNames = @($ExpectedTests |
        ForEach-Object { "TEST-$($_.ClassName).xml" } |
        Sort-Object)
    $actualReportNames = @($actualReports.Name | Sort-Object)
    $difference = @(Compare-Object `
        -ReferenceObject $expectedReportNames `
        -DifferenceObject $actualReportNames)
    if ($difference.Count -gt 0) {
        throw "$PhaseName report set is not exactly the expected $($ExpectedTests.Count) files:`n$($difference | Out-String)"
    }

    $total = 0
    foreach ($expected in $ExpectedTests) {
        $reportName = "TEST-$($expected.ClassName).xml"
        $reportPath = Join-Path $reportDirectory $reportName
        $reportItem = Get-Item -LiteralPath $reportPath
        if ($reportItem.LastWriteTimeUtc -lt
            $TestStartedUtc.AddSeconds(-5)) {
            throw "Surefire report is stale: $reportName"
        }

        $document = Read-SafeXml -PathValue $reportPath
        $suite = $document.DocumentElement
        if ($null -eq $suite -or $suite.LocalName -ne 'testsuite') {
            throw "Unexpected XML root in $reportName."
        }
        if ($suite.GetAttribute('name') -ne $expected.ClassName) {
            throw "Unexpected suite name in $reportName."
        }

        $tests = Get-RequiredIntegerAttribute `
            -Element $suite `
            -AttributeName 'tests' `
            -ReportName $reportName
        $failures = Get-RequiredIntegerAttribute `
            -Element $suite `
            -AttributeName 'failures' `
            -ReportName $reportName
        $errors = Get-RequiredIntegerAttribute `
            -Element $suite `
            -AttributeName 'errors' `
            -ReportName $reportName
        $skipped = Get-RequiredIntegerAttribute `
            -Element $suite `
            -AttributeName 'skipped' `
            -ReportName $reportName
        $testCases = @($suite.SelectNodes('testcase')).Count
        $failureNodes = @($suite.SelectNodes('testcase/failure')).Count
        $errorNodes = @($suite.SelectNodes('testcase/error')).Count
        $skippedNodes = @($suite.SelectNodes('testcase/skipped')).Count

        if ($tests -ne $expected.Tests -or
            $testCases -ne $expected.Tests -or
            $failures -ne 0 -or
            $failureNodes -ne 0 -or
            $errors -ne 0 -or
            $errorNodes -ne 0 -or
            $skipped -ne 0 -or
            $skippedNodes -ne 0) {
            throw "Unexpected test result in ${reportName}: tests=$tests, failures=$failures, errors=$errors, skipped=$skipped."
        }
        $total += $tests
        Write-Host ("PASS {0}: {1}" -f
            $expected.ShortName,
            $tests)
    }
    if ($total -ne $ExpectedTotalTests) {
        throw "$PhaseName expected $ExpectedTotalTests total tests, found $total."
    }
    Write-Host "$PhaseName passed: $total/$total."
}

function Get-TestSelector {
    param(
        [Parameter(Mandatory = $true)]
        [System.Object[]]$ExpectedTests,

        [Parameter(Mandatory = $true)]
        [string]$PhaseName
    )

    $names = @($ExpectedTests |
        ForEach-Object { [string]$_.ShortName })
    if ($names.Count -ne $ExpectedTests.Count -or
        @($names | Sort-Object -Unique).Count -ne $names.Count) {
        throw "$PhaseName contains missing or duplicate test names."
    }
    foreach ($name in $names) {
        if ($name -notmatch '^[A-Za-z][A-Za-z0-9_]*$') {
            throw "$PhaseName contains an invalid Maven test selector: $name"
        }
    }
    return ($names -join ',')
}

Assert-PostgresTarget
Assert-RedisTarget
Assert-Overlay
Assert-Toolchain

$classificationSelector = Get-TestSelector `
    -ExpectedTests $script:ExpectedClassificationTests `
    -PhaseName 'Classification verification'
$resetSelector = Get-TestSelector `
    -ExpectedTests $script:ExpectedResetTests `
    -PhaseName 'Project database reset'
$projectSelector = Get-TestSelector `
    -ExpectedTests $script:ExpectedProjectTests `
    -PhaseName 'Project regression'
$compileArguments = @(
    'clean',
    'test',
    '-DskipTests'
)
$connectionArguments = @(
    "-Dspring.datasource.url=$($script:PostgresUrl)",
    "-Dspring.datasource.username=$PostgresUser",
    "-Dspring.data.redis.host=$RedisHost",
    "-Dspring.data.redis.port=$RedisPort"
)
$destructiveArguments = @(
    '-Dsales.postgres.integration=true',
    '-Dsales.postgres.allow-destructive-target=true',
    "-Dsales.postgres.target-url=$($script:PostgresUrl)",
    "-Dsales.postgres.user=$PostgresUser"
)
$classificationArguments = @(
    "-Dtest=$classificationSelector"
) + $destructiveArguments + $connectionArguments + @(
    'test'
)
$resetArguments = @(
    'clean',
    "-Dtest=$resetSelector"
) + $destructiveArguments + $connectionArguments + @(
    'test'
)
$projectArguments = @(
    'clean',
    "-Dtest=$projectSelector"
) + $connectionArguments + @(
    '-Dspring.sql.init.mode=never',
    '-Dspring.rabbitmq.listener.simple.auto-startup=false',
    '-Dapp.dashboard.screen-refresh-millis=600000',
    '-Dapp.outbox.publish-delay-millis=600000',
    '-Dapp.order.expire-scan-delay-millis=600000',
    '-Dapp.stock-reservation.expire-scan-delay-millis=600000',
    '-Djunit.jupiter.execution.parallel.enabled=false',
    '-DforkCount=1',
    '-DreuseForks=true',
    'test'
)

Write-Host 'Sales classification verification preflight passed.'
Write-Host "Backend overlay: $($script:BackendDirectoryResolved)"
Write-Host "Overlay HEAD: $ExpectedBackendHead"
Write-Host "PostgreSQL target: $($script:PostgresUrl)"
Write-Host "Redis target: ${RedisHost}:${RedisPort}"
Write-Host "Java home: $($script:JdkHomeResolved)"
Write-Host "Maven: $($script:MavenCommandResolved)"
Write-Host ("Expected classification tests: {0} across {1} classes" -f
    $script:ExpectedClassificationTotalTests,
    $script:ExpectedClassificationTests.Count)
if ($IncludeProjectRegression) {
    Write-Host ("Expected project regression tests: {0} across {1} classes" -f
        $script:ExpectedProjectTotalTests,
        $script:ExpectedProjectTests.Count)
}

if ($DryRun) {
    Write-Host 'DRY RUN: no TCP connection or Maven command was executed.'
    Write-Host "Compile command: mvn $($compileArguments -join ' ')"
    Write-Host "Classification command: mvn $($classificationArguments -join ' ')"
    if ($IncludeProjectRegression) {
        Write-Host "Database reset command: mvn $($resetArguments -join ' ')"
        Write-Host "Project regression command: mvn $($projectArguments -join ' ')"
    }
    exit 0
}

$postgresPlain = $null
$redisPlain = $null
$environmentNames = @(
    'JAVA_HOME',
    'PATH',
    'SALES_POSTGRES_PASSWORD',
    'SPRING_DATASOURCE_PASSWORD',
    'SPRING_DATA_REDIS_PASSWORD'
)
$environmentBefore = @{}
foreach ($name in $environmentNames) {
    $environmentBefore[$name] =
        [System.Environment]::GetEnvironmentVariable(
            $name,
            [System.EnvironmentVariableTarget]::Process)
}

try {
    $postgresPlain = ConvertFrom-SecureValue `
        -Value $PostgresPassword
    $redisPlain = ConvertFrom-SecureValue `
        -Value $RedisPassword
    $script:Secrets = @($postgresPlain, $redisPlain)

    if ($postgresPlain.Contains("`0") -or
        $postgresPlain.Contains("`r") -or
        $postgresPlain.Contains("`n") -or
        $redisPlain.Contains("`0") -or
        $redisPlain.Contains("`r") -or
        $redisPlain.Contains("`n")) {
        throw 'Passwords cannot contain NUL, CR, or LF.'
    }
    $effectiveClassificationArguments = @($classificationArguments)
    $effectiveResetArguments = @($resetArguments)
    $effectiveProjectArguments = @($projectArguments)
    if ([string]::IsNullOrEmpty($postgresPlain)) {
        $effectiveClassificationArguments +=
            '-Dspring.datasource.password='
        $effectiveResetArguments +=
            '-Dspring.datasource.password='
        $effectiveProjectArguments +=
            '-Dspring.datasource.password='
    }
    if ([string]::IsNullOrEmpty($redisPlain)) {
        $effectiveClassificationArguments +=
            '-Dspring.data.redis.password='
        $effectiveResetArguments +=
            '-Dspring.data.redis.password='
        $effectiveProjectArguments +=
            '-Dspring.data.redis.password='
    }

    [System.Environment]::SetEnvironmentVariable(
        'JAVA_HOME',
        $script:JdkHomeResolved,
        [System.EnvironmentVariableTarget]::Process)
    [System.Environment]::SetEnvironmentVariable(
        'PATH',
        (Join-Path $script:JdkHomeResolved 'bin') +
            [System.IO.Path]::PathSeparator +
            $environmentBefore['PATH'],
        [System.EnvironmentVariableTarget]::Process)
    [System.Environment]::SetEnvironmentVariable(
        'SALES_POSTGRES_PASSWORD',
        $postgresPlain,
        [System.EnvironmentVariableTarget]::Process)
    [System.Environment]::SetEnvironmentVariable(
        'SPRING_DATASOURCE_PASSWORD',
        $postgresPlain,
        [System.EnvironmentVariableTarget]::Process)
    [System.Environment]::SetEnvironmentVariable(
        'SPRING_DATA_REDIS_PASSWORD',
        $redisPlain,
        [System.EnvironmentVariableTarget]::Process)

    Test-TcpListener `
        -HostValue $script:PostgresUri.Host `
        -PortValue $script:PostgresUri.Port `
        -Description 'PostgreSQL'
    Test-TcpListener `
        -HostValue $RedisHost `
        -PortValue $RedisPort `
        -Description 'Redis'

    Invoke-Maven `
        -Description 'Java 21 clean compilation' `
        -Arguments $compileArguments
    $testStartedUtc = [System.DateTime]::UtcNow
    Invoke-Maven `
        -Description 'Fixed 8-class Sales verification' `
        -Arguments $effectiveClassificationArguments
    Assert-SurefireReports `
        -TestStartedUtc $testStartedUtc `
        -ExpectedTests $script:ExpectedClassificationTests `
        -ExpectedTotalTests $script:ExpectedClassificationTotalTests `
        -PhaseName 'Classification verification'

    if ($IncludeProjectRegression) {
        $resetStartedUtc = [System.DateTime]::UtcNow
        Invoke-Maven `
            -Description 'Clean project database reset' `
            -Arguments $effectiveResetArguments
        Assert-SurefireReports `
            -TestStartedUtc $resetStartedUtc `
            -ExpectedTests $script:ExpectedResetTests `
            -ExpectedTotalTests $script:ExpectedResetTotalTests `
            -PhaseName 'Project database reset'

        $projectStartedUtc = [System.DateTime]::UtcNow
        Invoke-Maven `
            -Description 'Fixed 10-class project regression' `
            -Arguments $effectiveProjectArguments
        Assert-SurefireReports `
            -TestStartedUtc $projectStartedUtc `
            -ExpectedTests $script:ExpectedProjectTests `
            -ExpectedTotalTests $script:ExpectedProjectTotalTests `
            -PhaseName 'Project regression'
    }
} finally {
    foreach ($name in $environmentNames) {
        [System.Environment]::SetEnvironmentVariable(
            $name,
            $environmentBefore[$name],
            [System.EnvironmentVariableTarget]::Process)
    }
    $script:Secrets = @()
    $postgresPlain = $null
    $redisPlain = $null
}
