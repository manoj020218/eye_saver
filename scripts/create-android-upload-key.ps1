param(
  [string]$Alias = "eye-saver-upload",
  [int]$ValidityDays = 10000
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$androidRoot = Join-Path $repoRoot "apps/eye-saver/android"
$keyPath = Join-Path $androidRoot "eye-saver-upload.jks"
$propertiesPath = Join-Path $androidRoot "keystore.properties"

if (Test-Path -LiteralPath $keyPath) {
  throw "Upload key already exists at $keyPath. It was not overwritten."
}

$passwordSecure = Read-Host "Create a strong upload-key password (store it in a password manager)" -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($passwordSecure)
try {
  $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
  if ($password.Length -lt 12) { throw "Use at least 12 characters." }

  & keytool -genkeypair -v -keystore $keyPath -alias $Alias -keyalg RSA -keysize 4096 -validity $ValidityDays -storepass $password -keypass $password -dname "CN=Eye Saver Upload, OU=Mobile, O=Eye Saver, L=Unknown, ST=Unknown, C=IN"
  if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }

  $content = @(
    "storeFile=eye-saver-upload.jks"
    "storePassword=$password"
    "keyAlias=$Alias"
    "keyPassword=$password"
  ) -join [Environment]::NewLine
  [System.IO.File]::WriteAllText($propertiesPath, $content)
  Write-Host "Upload key created. Both files are ignored by Git. Back them up securely before publishing."
  Write-Host $keyPath
}
finally {
  if ($passwordPointer -ne [IntPtr]::Zero) {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
  }
}
