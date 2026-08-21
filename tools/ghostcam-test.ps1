param(
    [ValidateSet('doctor','camera','ipc','full')]
    [string]$Mode = 'doctor',
    [string]$CameraPackage = 'com.motorola.camera3',
    [string]$ModulePackage = 'io.github.zensu357.camswap',
    [switch]$NoLaunch
)

$ErrorActionPreference = 'Stop'
$script:Failed = $false

function Write-Step([string]$Message) {
    Write-Host "`n[GHOSTCAM] $Message"
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory=$true)][string[]]$Args,
        [switch]$AllowFailure,
        [switch]$Quiet
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & adb @Args 2>&1
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($output -and -not $Quiet) { $output | ForEach-Object { Write-Host $_ } }
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "adb failed ($code): adb $($Args -join ' ')"
    }
    return ,$output
}

function Assert-Device {
    Write-Step 'Checking ADB device'
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = & adb devices 2>&1
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($code -ne 0) { throw 'adb was not found or failed.' }
    $devices = @($lines | Select-String -Pattern "\tdevice$")
    if ($devices.Count -ne 1) {
        throw "Expected exactly one authorized device. Found $($devices.Count). Run: adb devices"
    }
    Write-Host "OK: $($devices[0].Line)"
}

function Show-PackageInfo {
    Write-Step 'Checking installed GHOSTCAM build'
    $pkg = Invoke-Adb -Args @('shell','dumpsys','package',$ModulePackage) -Quiet
    $pkg | Select-String -Pattern 'versionName|versionCode|lastUpdateTime' |
        ForEach-Object { Write-Host $_.Line }
}

function Show-VectorState {
    Write-Step 'Checking Vector scope'
    $scopeCommand = "/data/adb/lspd/cli scope ls $ModulePackage"
    $scope = Invoke-Adb -Args @('shell','su','-c',$scopeCommand) -AllowFailure -Quiet
    if ($scope) {
        $scope | ForEach-Object { Write-Host $_ }
        $cameraScoped = $scope | Select-String -SimpleMatch $CameraPackage
        if (-not $cameraScoped) {
            $script:Failed = $true
            Write-Warning "$CameraPackage is not present in Vector scope for $ModulePackage."
        }
    } else {
        $script:Failed = $true
        Write-Warning 'Vector scope query returned no output.'
    }
}

function Launch-Camera {
    if ($NoLaunch) { return }
    Write-Step "Restarting $CameraPackage"
    Invoke-Adb -Args @('shell','am','force-stop',$CameraPackage) -Quiet
    Start-Sleep -Milliseconds 500
    Invoke-Adb -Args @('shell','monkey','-p',$CameraPackage,'1') -Quiet
    Start-Sleep -Seconds 3
}

function Get-CameraPid {
    $pidLine = Invoke-Adb -Args @('shell','pidof',$CameraPackage) -AllowFailure -Quiet
    $pidValue = ($pidLine | Select-Object -First 1).ToString().Trim()
    if (-not $pidValue) { throw "$CameraPackage is not running." }
    Write-Host "Camera PID: $pidValue"
    return $pidValue
}

function Show-InjectionEvidence {
    Write-Step 'Checking Vector/CamSwap injection'
    $logs = Invoke-Adb -Args @('logcat','-d') -Quiet
    $hits = $logs | Select-String -Pattern 'VectorModuleManager.*camswap|LibXposed.*process=com\.motorola\.camera3|onPackageReady: package=com\.motorola\.camera3|NativeHook.*init result=true|ImageReader:'
    if (-not $hits) {
        $script:Failed = $true
        Write-Warning 'No CamSwap injection evidence found.'
    } else {
        $hits | Select-Object -Last 30 | ForEach-Object { Write-Host $_.Line }
    }
}

function Show-ReceiverState {
    Write-Step 'Inspecting ACTION_UPDATE_CONFIG receivers'
    $dump = Invoke-Adb -Args @('shell','dumpsys','activity','broadcasts') -Quiet
    $hits = $dump | Select-String -Pattern 'io\.github\.zensu357\.camswap\.ACTION_UPDATE_CONFIG|Exported Denial|not specifying RECEIVER_EXPORTED|com\.motorola\.camera3'
    $hits | Select-Object -Last 120 | ForEach-Object { Write-Host $_.Line }
    if ($hits | Select-String -Pattern 'not specifying RECEIVER_EXPORTED') {
        Write-Warning 'Detected at least one NOT_EXPORTED receiver for ACTION_UPDATE_CONFIG.'
    }
}

function Test-Ipc {
    Write-Step 'Testing GHOSTCAM host to camera IPC'
    Invoke-Adb -Args @('logcat','-c') -Quiet
    Launch-Camera
    Start-Sleep -Seconds 3
    $logs = Invoke-Adb -Args @('logcat','-d') -Quiet
    $pattern = 'config request broadcast sent|CS-Host|config broadcast|Binder|video_binder|privateCache|forcePrivate|ACTION_UPDATE_CONFIG|Exported Denial'
    $hits = $logs | Select-String -Pattern $pattern
    if ($hits) {
        $hits | ForEach-Object { Write-Host $_.Line }
    } else {
        $script:Failed = $true
        Write-Warning 'No IPC evidence found.'
    }
}

function Test-ManualUpdate {
    Write-Step 'Testing exported ACTION_UPDATE_CONFIG receiver with minimal JSON'
    Invoke-Adb -Args @('logcat','-c') -Quiet
    Invoke-Adb -Args @('shell','am','broadcast','-a','io.github.zensu357.camswap.ACTION_UPDATE_CONFIG','-p',$CameraPackage,'--es','config_json','{}') -Quiet
    Start-Sleep -Seconds 1
    $logs = Invoke-Adb -Args @('logcat','-d') -Quiet
    $hits = $logs | Select-String -Pattern 'CamSwap|ACTION_UPDATE_CONFIG|Exported Denial|config'
    $hits | ForEach-Object { Write-Host $_.Line }
}

Write-Host 'GHOSTCAM SAFE ADB TEST RUNNER'
Write-Host 'Read-only/diagnostic policy: no flashing, erasing, Magisk deletion, partition writes, or rm -rf.'

Assert-Device
Show-PackageInfo

switch ($Mode) {
    'doctor' {
        Show-VectorState
        Launch-Camera
        Get-CameraPid | Out-Null
        Show-InjectionEvidence
        Show-ReceiverState
    }
    'camera' {
        Launch-Camera
        Get-CameraPid | Out-Null
        Show-InjectionEvidence
    }
    'ipc' {
        Test-Ipc
        Show-ReceiverState
    }
    'full' {
        Show-VectorState
        Launch-Camera
        Get-CameraPid | Out-Null
        Show-InjectionEvidence
        Test-Ipc
        Test-ManualUpdate
        Show-ReceiverState
    }
}

Write-Step 'Result'
if ($script:Failed) {
    Write-Host 'DIAGNOSTIC_RESULT=FAIL'
    exit 2
}
Write-Host 'DIAGNOSTIC_RESULT=OK'
exit 0
