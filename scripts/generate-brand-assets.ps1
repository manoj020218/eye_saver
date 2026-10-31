param(
  [string]$IconSource = "branding/eye-saver-mark-1024.png",
  [string]$FeatureSource = "branding/eye-saver-feature-source.png"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$repoRoot = Split-Path -Parent $PSScriptRoot
$iconPath = Join-Path $repoRoot $IconSource
$featurePath = Join-Path $repoRoot $FeatureSource
$resRoot = Join-Path $repoRoot "apps/eye-saver/android/app/src/main/res"
$storeRoot = Join-Path $repoRoot "play-store/assets"

function Save-Png($bitmap, [string]$path) {
  $directory = Split-Path -Parent $path
  New-Item -ItemType Directory -Force -Path $directory | Out-Null
  $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
}

function Draw-ContainedImage($graphics, $image, [int]$canvasWidth, [int]$canvasHeight, [double]$scale) {
  $targetWidth = [int]($canvasWidth * $scale)
  $targetHeight = [int]($targetWidth * $image.Height / $image.Width)
  if ($targetHeight -gt [int]($canvasHeight * $scale)) {
    $targetHeight = [int]($canvasHeight * $scale)
    $targetWidth = [int]($targetHeight * $image.Width / $image.Height)
  }
  $x = [int](($canvasWidth - $targetWidth) / 2)
  $y = [int](($canvasHeight - $targetHeight) / 2)
  $graphics.DrawImage($image, $x, $y, $targetWidth, $targetHeight)
}

function New-Canvas([int]$width, [int]$height, [System.Drawing.Color]$background) {
  $bitmap = New-Object System.Drawing.Bitmap($width, $height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
  $graphics.Clear($background)
  $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
  $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
  return @($bitmap, $graphics)
}

$icon = [System.Drawing.Image]::FromFile($iconPath)
try {
  $foreground = New-Canvas 432 432 ([System.Drawing.Color]::Transparent)
  Draw-ContainedImage $foreground[1] $icon 432 432 0.66
  Save-Png $foreground[0] (Join-Path $resRoot "drawable-nodpi/eye_saver_foreground.png")
  $foreground[1].Dispose(); $foreground[0].Dispose()

  $splash = New-Canvas 512 512 ([System.Drawing.Color]::Transparent)
  Draw-ContainedImage $splash[1] $icon 512 512 0.54
  Save-Png $splash[0] (Join-Path $resRoot "drawable-nodpi/eye_saver_splash.png")
  $splash[1].Dispose(); $splash[0].Dispose()

  $densities = @{
    "mipmap-mdpi" = 48
    "mipmap-hdpi" = 72
    "mipmap-xhdpi" = 96
    "mipmap-xxhdpi" = 144
    "mipmap-xxxhdpi" = 192
  }
  foreach ($entry in $densities.GetEnumerator()) {
    $legacy = New-Canvas $entry.Value $entry.Value ([System.Drawing.Color]::FromArgb(255, 4, 35, 28))
    Draw-ContainedImage $legacy[1] $icon $entry.Value $entry.Value 0.76
    Save-Png $legacy[0] (Join-Path $resRoot "$($entry.Key)/ic_launcher.png")
    Save-Png $legacy[0] (Join-Path $resRoot "$($entry.Key)/ic_launcher_round.png")
    $legacy[1].Dispose(); $legacy[0].Dispose()
  }

  $storeIcon = New-Canvas 512 512 ([System.Drawing.Color]::FromArgb(255, 4, 35, 28))
  Draw-ContainedImage $storeIcon[1] $icon 512 512 0.76
  Save-Png $storeIcon[0] (Join-Path $storeRoot "app-icon-512.png")
  $storeIcon[1].Dispose(); $storeIcon[0].Dispose()
}
finally {
  $icon.Dispose()
}

$feature = [System.Drawing.Image]::FromFile($featurePath)
try {
  $canvas = New-Canvas 1024 500 ([System.Drawing.Color]::FromArgb(255, 3, 27, 22))
  $sourceRatio = $feature.Width / $feature.Height
  $targetRatio = 1024 / 500
  if ($sourceRatio -gt $targetRatio) {
    $cropHeight = $feature.Height
    $cropWidth = [int]($cropHeight * $targetRatio)
    $cropX = [int](($feature.Width - $cropWidth) / 2)
    $cropY = 0
  } else {
    $cropWidth = $feature.Width
    $cropHeight = [int]($cropWidth / $targetRatio)
    $cropX = 0
    $cropY = [int](($feature.Height - $cropHeight) / 2)
  }
  $sourceRect = New-Object System.Drawing.Rectangle($cropX, $cropY, $cropWidth, $cropHeight)
  $targetRect = New-Object System.Drawing.Rectangle(0, 0, 1024, 500)
  $canvas[1].DrawImage($feature, $targetRect, $sourceRect, [System.Drawing.GraphicsUnit]::Pixel)
  Save-Png $canvas[0] (Join-Path $storeRoot "feature-graphic-1024x500.png")
  $canvas[1].Dispose(); $canvas[0].Dispose()
}
finally {
  $feature.Dispose()
}

Write-Host "Android launcher, splash and Play Store brand assets generated."
