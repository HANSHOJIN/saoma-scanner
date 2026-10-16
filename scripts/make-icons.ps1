Add-Type -AssemblyName System.Drawing

$sizes = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
$root = 'F:\codex\saoma\app\src\main\res'

foreach ($k in $sizes.Keys) {
    $s = $sizes[$k]
    $bmp = New-Object System.Drawing.Bitmap($s, $s)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $bg = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(46, 125, 50))
    $fg = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)

    # rounded square background
    $pad = [math]::Round($s * 0.06)
    $rad = [math]::Round($s * 0.22)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $rad * 2
    $path.AddArc($pad, $pad, $d, $d, 180, 90)
    $path.AddArc(($s - $pad - $d), $pad, $d, $d, 270, 90)
    $path.AddArc(($s - $pad - $d), ($s - $pad - $d), $d, $d, 0, 90)
    $path.AddArc($pad, ($s - $pad - $d), $d, $d, 90, 90)
    $path.CloseFigure()
    $g.FillPath($bg, $path)

    # barcode bars
    $bars = @(
        @(0.26, 0.045), @(0.33, 0.03), @(0.39, 0.055), @(0.47, 0.03),
        @(0.53, 0.045), @(0.60, 0.03), @(0.66, 0.055)
    )
    $top = $s * 0.30
    $h = $s * 0.40
    foreach ($b in $bars) {
        $x = $s * $b[0]
        $w = [math]::Max(1.0, $s * $b[1])
        $g.FillRectangle($fg, [single]$x, [single]$top, [single]$w, [single]$h)
    }

    $g.Dispose()
    $dir = Join-Path $root "mipmap-$k"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $bmp.Save((Join-Path $dir 'ic_launcher.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "wrote $dir\ic_launcher.png ($s x $s)"
}
