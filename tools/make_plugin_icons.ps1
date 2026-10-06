# Write the repository icon for every published plugin.
#
# Settings -> Extensions renders plugins.json's iconUrl at 32dp with fitCenter
# (repository_item.xml), so a plain square is what it wants. There are no real
# logos to use, so each icon is the plugin's initials on its own colour. Commit
# the generated PNGs; run this again only when a colour or the set changes.
#
#   powershell -NoProfile -File tools/make_plugin_icons.ps1
[CmdletBinding()]
param(
    [int]$Size = 128
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot

# module, initials, background, text colour
$icons = @(
    @{ Module = 'hianime';        Initials = 'HA';  Background = '#E4572E'; Text = '#FFFFFF' }
    @{ Module = 'anikoto';        Initials = 'AK';  Background = '#7B4B94'; Text = '#FFFFFF' }
    @{ Module = 'animecube';      Initials = 'AC';  Background = '#2A9D8F'; Text = '#FFFFFF' }
    @{ Module = 'hdhub4u';        Initials = 'HD';  Background = '#3A86FF'; Text = '#FFFFFF' }
    @{ Module = 'bollyflix';      Initials = 'BF';  Background = '#E76F51'; Text = '#FFFFFF' }
    @{ Module = 'yts';            Initials = 'YTS'; Background = '#FFB703'; Text = '#1F2933' }
    @{ Module = 'prowlarr';       Initials = 'PL';  Background = '#4A6FA5'; Text = '#FFFFFF' }
    @{ Module = 'torrin';         Initials = 'TR';  Background = '#4C956C'; Text = '#FFFFFF' }
    @{ Module = 'torrin-mdblist'; Initials = 'ML';  Background = '#6D597A'; Text = '#FFFFFF' }
    @{ Module = 'torrin-trakt';   Initials = 'TK';  Background = '#1D3557'; Text = '#FFFFFF' }
)

function New-RoundedPath([System.Drawing.RectangleF]$rect, [float]$radius) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $diameter = $radius * 2
    $path.AddArc($rect.X, $rect.Y, $diameter, $diameter, 180, 90)
    $path.AddArc($rect.Right - $diameter, $rect.Y, $diameter, $diameter, 270, 90)
    $path.AddArc($rect.Right - $diameter, $rect.Bottom - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($rect.X, $rect.Bottom - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    return $path
}

foreach ($icon in $icons) {
    $repoDir = Join-Path $root ('plugins/' + $icon.Module + '/repo')
    if (-not (Test-Path (Join-Path $repoDir 'plugins.json'))) {
        Write-Output ("  " + $icon.Module.PadRight(16) + "not published, skipped")
        continue
    }

    $bitmap = New-Object System.Drawing.Bitmap $Size, $Size
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
        $graphics.Clear([System.Drawing.Color]::Transparent)

        $shape = New-RoundedPath (New-Object System.Drawing.RectangleF 0, 0, $Size, $Size) ($Size * 0.22)
        try {
            $graphics.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.ColorTranslator]::FromHtml($icon.Background))), $shape)
        } finally {
            $shape.Dispose()
        }

        $pointSize = if ($icon.Initials.Length -ge 3) { $Size * 0.34 } else { $Size * 0.44 }
        $font = New-Object System.Drawing.Font 'Segoe UI', $pointSize, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
        $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.ColorTranslator]::FromHtml($icon.Text))
        $format = New-Object System.Drawing.StringFormat
        $format.Alignment = [System.Drawing.StringAlignment]::Center
        $format.LineAlignment = [System.Drawing.StringAlignment]::Center

        $graphics.DrawString($icon.Initials, $font, $brush, (New-Object System.Drawing.RectangleF 0, 0, $Size, $Size), $format)

        $target = Join-Path $repoDir 'icon.png'

        # Idempotent: only write if the file is missing or bytes differ. GDI+
        # doesn't produce byte-identical PNGs across runs, so comparing the
        # rendered bytes avoids churning unrelated icons in git.
        $rendered = New-Object System.IO.MemoryStream
        $bitmap.Save($rendered, [System.Drawing.Imaging.ImageFormat]::Png)
        $newBytes = $rendered.ToArray()
        $rendered.Dispose()

        $write = $true
        if (Test-Path $target) {
            $oldBytes = [System.IO.File]::ReadAllBytes($target)
            if ($oldBytes.Length -eq $newBytes.Length) {
                $same = $true
                for ($i = 0; $i -lt $oldBytes.Length; $i++) {
                    if ($oldBytes[$i] -ne $newBytes[$i]) { $same = $false; break }
                }
                if ($same) { $write = $false }
            }
        }

        if ($write) {
            [System.IO.File]::WriteAllBytes($target, $newBytes)
        }

        $format.Dispose()
        $brush.Dispose()
        $font.Dispose()

        $status = if ($write) { "written" } else { "unchanged" }
        Write-Output ("  " + $icon.Module.PadRight(16) + "plugins/" + $icon.Module + "/repo/icon.png  " + $icon.Initials + "  " + $icon.Background + "  " + $status)
    } finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}