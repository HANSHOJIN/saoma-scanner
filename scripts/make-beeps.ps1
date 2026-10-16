# Generate notification sounds for the scanner app.
#   beep_ok.wav  - short crisp "di" beep for a successful record
#   beep_dup.wav - lower double beep for an already-recorded (duplicate) barcode
# Both are plain 16-bit mono PCM WAV so SoundPool can play them with low latency.
# NOTE: keep this file ASCII-only -- Windows PowerShell 5.1 reads BOM-less files as ANSI.

$root = $PSScriptRoot
if (-not $root) { $root = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $root) { $root = 'F:\codex\saoma' }
$root = (Resolve-Path (Join-Path $root '..')).Path

$outDir = Join-Path $root 'app\src\main\res\raw'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$outDir = (Resolve-Path $outDir).Path

function Write-Wav {
    param(
        [string] $path,
        [double[]] $samples,
        [int] $sampleRate
    )
    $stream = New-Object System.IO.MemoryStream
    $writer = New-Object System.IO.BinaryWriter($stream)
    $dataSize = $samples.Length * 2
    $writer.Write([System.Text.Encoding]::ASCII.GetBytes('RIFF'))
    $writer.Write([int](36 + $dataSize))
    $writer.Write([System.Text.Encoding]::ASCII.GetBytes('WAVE'))
    $writer.Write([System.Text.Encoding]::ASCII.GetBytes('fmt '))
    $writer.Write([int]16)
    $writer.Write([int16]1)
    $writer.Write([int16]1)
    $writer.Write([int]$sampleRate)
    $writer.Write([int]($sampleRate * 2))
    $writer.Write([int16]2)
    $writer.Write([int16]16)
    $writer.Write([System.Text.Encoding]::ASCII.GetBytes('data'))
    $writer.Write([int]$dataSize)
    foreach ($s in $samples) {
        $clamped = [math]::Max(-1.0, [math]::Min(1.0, $s))
        $writer.Write([int16]($clamped * 32000))
    }
    $writer.Flush()
    [System.IO.File]::WriteAllBytes($path, $stream.ToArray())
    $writer.Dispose()
    $stream.Dispose()
}

$rate = 22050

# Success: single crisp beep, 2600 Hz, 60 ms, fast decay
$len1 = [int][math]::Round([double]$rate * 0.060)
$ok = New-Object double[] $len1
for ($i = 0; $i -lt $len1; $i++) {
    $t = [double]$i / $rate
    $attack = [math]::Min(1.0, $t / 0.0015)
    $decay = [math]::Exp(-$t * 42.0)
    $ok[$i] = [math]::Sin(2.0 * [math]::PI * 2600.0 * $t) * $attack * $decay * 0.85
}
Write-Wav -path (Join-Path $outDir 'beep_ok.wav') -samples $ok -sampleRate $rate

# Duplicate: two lower pips, 900 Hz, 45 ms each with a 70 ms gap
$pip = 0.045
$gap = 0.070
$len2 = [int][math]::Round([double]$rate * ($pip + $pip + $gap))
$dup = New-Object double[] $len2
for ($i = 0; $i -lt $len2; $i++) {
    $t = [double]$i / $rate
    $local = -1.0
    if ($t -lt $pip) {
        $local = $t
    } elseif ($t -gt ($pip + $gap) -and $t -lt ($pip + $gap + $pip)) {
        $local = $t - ($pip + $gap)
    }
    if ($local -lt 0.0) {
        $dup[$i] = 0.0
    } else {
        $attack = [math]::Min(1.0, $local / 0.002)
        $decay = [math]::Exp(-$local * 30.0)
        $dup[$i] = [math]::Sin(2.0 * [math]::PI * 900.0 * $local) * $attack * $decay * 0.9
    }
}
Write-Wav -path (Join-Path $outDir 'beep_dup.wav') -samples $dup -sampleRate $rate

Get-ChildItem $outDir -Filter '*.wav' | Select-Object Name, Length
