param([Parameter(Mandatory=$true)][string]$Url)
$ErrorActionPreference = 'Stop'
$client = New-Object System.Net.WebClient
try {
    $bytes = $client.DownloadData($Url)
    $stream = [Console]::OpenStandardOutput()
    $stream.Write($bytes, 0, $bytes.Length)
} finally { $client.Dispose() }
