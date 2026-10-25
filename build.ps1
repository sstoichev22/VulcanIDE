# Builds the IDE jar "build/latest/VulcanIDE <version>.jar" (bundles ../intel8080, ../c8080 and ../vulcanos/stdlib).
# Regression tests live in ../qa (qa\run-all.ps1). Also refreshes an existing Desktop shortcut.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ws = Split-Path -Parent $projectRoot
$buildDirectory = Join-Path $projectRoot 'build'
$classesDirectory = Join-Path $buildDirectory 'classes'
$jdkDirectory = Get-ChildItem -LiteralPath (Join-Path $env:ProgramFiles 'Java') -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } | Select-Object -Last 1
if ($jdkDirectory) { $javac = Join-Path $jdkDirectory.FullName 'bin\javac.exe'; $java = Join-Path $jdkDirectory.FullName 'bin\java.exe'; $jar = Join-Path $jdkDirectory.FullName 'bin\jar.exe' }
else { $javac = (Get-Command javac).Source; $java = (Get-Command java).Source; $jar = (Get-Command jar).Source }
if (Test-Path $classesDirectory) { Remove-Item $classesDirectory -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classesDirectory | Out-Null
$sources = 'intel8080\src', 'c8080\src', 'vulcanide\src' | ForEach-Object { Get-ChildItem (Join-Path $ws $_) -Recurse -File -Filter *.java } | ForEach-Object FullName
& $javac --release 17 -encoding UTF-8 -d $classesDirectory @sources
if ($LASTEXITCODE -ne 0) { throw 'Application compilation failed.' }
Copy-Item (Join-Path $projectRoot 'src\VulcanIDE\res') (Join-Path $classesDirectory 'VulcanIDE\res') -Recurse -Force
foreach ($d in 'include','lib') { Copy-Item (Join-Path $ws "vulcanos\stdlib\$d") (Join-Path $classesDirectory "stdlib\$d") -Recurse -Force }
Copy-Item (Join-Path $projectRoot 'docs') (Join-Path $classesDirectory 'docs') -Recurse -Force
$latestBuildDirectory = Join-Path $buildDirectory 'latest'
New-Item -ItemType Directory -Force -Path $latestBuildDirectory | Out-Null
$version = [regex]::Match((Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'src\VulcanIDE\Vulcan.java')), 'VERSION\s*=\s*"([^"]+)"').Groups[1].Value
if (-not $version) { throw 'VERSION was not found in src/VulcanIDE/Vulcan.java.' }
$jarPath = Join-Path $latestBuildDirectory "VulcanIDE $version.jar"
& $jar "-J-Djava.io.tmpdir=$buildDirectory" --create --file $jarPath --main-class VulcanIDE.Vulcan -C $classesDirectory .
if ($LASTEXITCODE -ne 0) { throw 'Application packaging failed.' }
foreach ($old in Get-ChildItem -LiteralPath $latestBuildDirectory -File | Where-Object { ($_.Name -like 'VulcanIDE *.jar' -or $_.Name -eq 'VulcanIDE-latest.jar') -and $_.FullName -ne $jarPath }) {
    try { Remove-Item -LiteralPath $old.FullName -Force }
    catch { throw "Close Vulcan before packaging: $($old.Name) is in use." }
}        # A .jar takes its Explorer icon from the file association, so ship a Vulcan .ico and a shortcut that uses it.
Add-Type -AssemblyName System.Drawing
$iconPath = Join-Path $latestBuildDirectory 'vulcan.ico'
$sourceIcon = [System.Drawing.Image]::FromFile((Join-Path $projectRoot 'src\VulcanIDE\res\vulcan-icon.png'))
$frames = @()
try {
    foreach ($size in 256, 64, 48, 32, 16) {
        $bitmap = New-Object System.Drawing.Bitmap $size, $size
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.DrawImage($sourceIcon, 0, 0, $size, $size)
        $graphics.Dispose()
        $stream = New-Object System.IO.MemoryStream
        $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
        $bitmap.Dispose()
        $frames += ,@($size, $stream.ToArray())
    }
} finally { $sourceIcon.Dispose() }
$ico = New-Object System.IO.MemoryStream
$writer = New-Object System.IO.BinaryWriter $ico
$writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$frames.Count)
$offset = 6 + 16 * $frames.Count
foreach ($frame in $frames) {
    $dimension = if ($frame[0] -ge 256) { 0 } else { $frame[0] }
    $writer.Write([byte]$dimension); $writer.Write([byte]$dimension); $writer.Write([byte]0); $writer.Write([byte]0)
    $writer.Write([uint16]1); $writer.Write([uint16]32); $writer.Write([uint32]$frame[1].Length); $writer.Write([uint32]$offset)
    $offset += $frame[1].Length
}
foreach ($frame in $frames) { $writer.Write([byte[]]$frame[1]) }
$writer.Flush()
[System.IO.File]::WriteAllBytes($iconPath, $ico.ToArray())
$writer.Dispose()
$javaw = Join-Path (Split-Path -Parent $java) 'javaw.exe'
if (-not (Test-Path -LiteralPath $javaw)) { $javaw = 'javaw.exe' }
$shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $latestBuildDirectory 'VulcanIDE.lnk'))
$shortcut.TargetPath = $javaw
$shortcut.Arguments = '-jar "' + $jarPath + '"'
$shortcut.WorkingDirectory = $latestBuildDirectory
$shortcut.IconLocation = $iconPath + ',0'
$shortcut.Description = 'Vulcan IDE'
$shortcut.Save()
Write-Output "Created $jarPath"
Write-Output "Created $(Join-Path $latestBuildDirectory 'VulcanIDE.lnk') (Vulcan icon launcher)"
# Refresh an existing Desktop launcher so it follows the versioned JAR; never create one unasked.
$desktopShortcut = Join-Path ([Environment]::GetFolderPath('Desktop')) 'VulcanIDE.lnk'
if (Test-Path -LiteralPath $desktopShortcut) {
    Copy-Item -LiteralPath (Join-Path $latestBuildDirectory 'VulcanIDE.lnk') -Destination $desktopShortcut -Force
    Write-Output "Updated $desktopShortcut"
}


