param(
  [Parameter(Mandatory=$true)][string]$Path,
  [string]$Lang = "zh-Hans-CN"
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
$null = [Windows.Media.Ocr.OcrEngine,Windows.Foundation,ContentType=WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder,Windows.Graphics.Imaging,ContentType=WindowsRuntime]
$null = [Windows.Globalization.Language,Windows.Globalization,ContentType=WindowsRuntime]

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
  $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($WinRtTask, $ResultType) {
  $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
  $netTask = $asTask.Invoke($null, @($WinRtTask))
  try {
    $netTask.Wait(-1) | Out-Null
  } catch {
    $inner = $_.Exception
    while ($inner.InnerException) { $inner = $inner.InnerException }
    throw ("await failed: " + $inner.Message)
  }
  $netTask.Result
}

$full = (Resolve-Path $Path).Path
$fs = [System.IO.File]::OpenRead($full)
$ras = [System.IO.WindowsRuntimeStreamExtensions]::AsRandomAccessStream($fs)
$decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($ras)) ([Windows.Graphics.Imaging.BitmapDecoder])
$bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
$language = [Windows.Globalization.Language]::new($Lang)
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($language)
if (-not $engine) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages() }
$result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])

$i = 0
foreach ($line in $result.Lines) {
  $txt = $line.Text
  $words = @($line.Words)
  if ($words.Count -eq 0) { continue }
  $r0 = $words[0].BoundingRect
  $x0 = [int]$r0.X
  $y0 = [int]$r0.Y
  $lastR = $words[$words.Count - 1].BoundingRect
  $x1 = [int]$lastR.X + [int]$lastR.Width
  $h = [int]$r0.Height
  $cx = [int](($x0 + $x1) / 2)
  $cy = [int]($y0 + ($h / 2))
  "{0,4},{1,4} w={2,-4} h={3,-3} {4}" -f $cx, $cy, ($x1 - $x0), $h, $txt
  $i++
}
$fs.Close()