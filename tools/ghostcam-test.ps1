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
        [switch]$AllowFailure
    )

    $output = & adb @Args 2>&1
    $code = $LASTEXITCODE
    if ($output) { $output | ForEach-Object { Write-Host $_ } }
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "adb failed ($code): adb $($Args -join ' ')"
    }
    return ,$output
}

function Assert-Device {
    Write-Step 'Checking ADB device'
    $lines = & adb devices 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'adb was not found or failed.' }
    $devices = @($lines | Select-String -Pattern "\tdevice$")
    if ($devices.Count -ne 1) {
        throw "Expected exactly one authorized device. Found $($devices.Count). Run: adb devices"
    }
    Write-Host "OK: $($devices[0].Line)"
}

function Show-PackageInfo {
    Write-Step 'Checking installed GHOSTCAM build'
    Invoke-Adb -Args @('shell','dumpsys','package',$ModulePackage) |
        Select-String -Pattern 'versionName|versionCode|lastUpdateTime' |
        ForEach-Object { Write-Host $_.Line }
}

function Show-VectorState {
    Write-Step 'Checking Vector scope'
    Invoke-Adb -Args @('shell','su','-c',"/data/adb/lspd/cli scope list $ModulePackage") -AllowFailure |
        ForEach-Object { Write-Host $_ }
}

function Launch-Camera {
    if ($NoLaunch) { return }
    Write-Step "Restarting $CameraPackage"
    Invoke-Adb -Args @('shell','am','force-stop',$CameraPackage)
    Start-Sleep -Milliseconds 500
    Invoke-Adb -Args @('shell','monkey','-p',$CameraPackage,'1')
    Start-Sleep -Seconds 3
}

function Get-CameraPid {
    $pidLine = Invoke-Adb -Args @('shell','pidof',$CameraPackage) -AllowFailure
    $pidValue = ($pidLine | Select-Object -First 1).ToString().Trim()
    if (-not $pidValue) { throw "$CameraPackage is not running." }
    Write-Host "Camera PID: $pidValue"
    return $pidValue
}

function Show-InjectionEvidence {
    Write-Step 'Checking Vector/CamSwap injection'
    $logs = Invoke-Adb -Args @('logcat','-d')
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
    $dump = Invoke-Adb -Args @('shell','dumpsys','activity','broadcasts')
    $hits = $dump | Select-String -Pattern 'io\.github\.zensu357\.camswap\.ACTION_UPDATE_CONFIG|Exported Denial|not specifying RECEIVER_EXPORTED|com\.motorola\.camera3'
    $hits | Select-Object -Last 120 | ForEach-Object { Write-Host $_.Line }
    if ($hits | Select-String -Pattern 'not specifying RECEIVER_EXPORTED') {
        Write-Warning 'Detected at least one NOT_EXPORTED receiver for ACTION_UPDATE_CONFIG.'
    }
}

function Test-Ipc {
    Write-Step 'Testing GHOSTCAM host -> camera IPC'
    Invoke-Adb -Args @('logcat','-c')
    Launch-Camera
    Start-Sleep -Seconds 3
    $logs = Invoke-Adb -Args @('logcat','-d')
    $pattern = 'config request broadcast sent|CS-Host|配置广播已发送到|配置更新|Binder 视频 FD|video_binder|privateCache|forcePrivate|ACTION_UPDATE_CONFIG|Exported Denial'
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
    Invoke-Adb -Args @('logcat','-c')
    Invoke-Adb -Args @('shell','am','broadcast','-a','io.github.zensu357.camswap.ACTION_UPDATE_CONFIG','-p',$CameraPackage,'--es','config_json','{}')
    Start-Sleep -Seconds 1
    $logs = Invoke-Adb -Args @('logcat','-d')
    $hits = $logs | Select-String -Pattern 'CamSwap|配置更新|ACTION_UPDATE_CONFIG|Exported Denial'
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
