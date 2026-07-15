Set-Location $PSScriptRoot
gradle --refresh-dependencies clean build
if (Test-Path "build\libs") { Get-ChildItem "build\libs" }
