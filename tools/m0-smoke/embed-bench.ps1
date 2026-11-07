<#
  Measures embedding latency and (optionally) a file-level Recall@5 spot check.
  Usage:
    .\embed-bench.ps1 -RepoPath D:\dev\targets\spring-petclinic-rest -Model nomic-embed-text `
      -DocPrefix 'search_document: ' -QueryPrefix 'search_query: '
    .\embed-bench.ps1 ... -MaxChunks 100000 -QueriesFile .\spot-queries.json
#>
param(
  [Parameter(Mandatory)][string]$RepoPath,
  [Parameter(Mandatory)][string]$Model,
  [int]$MaxChunks = 100,
  [int]$ChunkLines = 40,
  [int]$BatchSize = 16,
  [string]$DocPrefix = '',
  [string]$QueryPrefix = '',
  [string]$QueriesFile = '',
  [string]$BaseUrl = 'http://localhost:11434'
)
$ErrorActionPreference = 'Stop'
$RepoPath = (Resolve-Path -LiteralPath $RepoPath).Path.TrimEnd('\')

function Invoke-Embed([string[]]$Texts) {
  $json  = @{ model = $Model; input = $Texts } | ConvertTo-Json -Depth 5
  $bytes = [Text.Encoding]::UTF8.GetBytes($json)
  $res = Invoke-RestMethod -Uri "$BaseUrl/api/embed" -Method Post `
           -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 900
  return ,$res.embeddings
}

function Get-Cosine([double[]]$a, [double[]]$b) {
  $dot = 0.0; $na = 0.0; $nb = 0.0
  for ($i = 0; $i -lt $a.Length; $i++) { $dot += $a[$i]*$b[$i]; $na += $a[$i]*$a[$i]; $nb += $b[$i]*$b[$i] }
  return $dot / ([Math]::Sqrt($na) * [Math]::Sqrt($nb))
}

# 1. Fixed-window chunks over Java sources (good enough for a smoke test)
$files = Get-ChildItem -LiteralPath $RepoPath -Recurse -File -Filter *.java |
  Where-Object { $_.FullName -notmatch '\\(target|build|node_modules|\.git)\\' } | Sort-Object FullName
$chunks = New-Object System.Collections.Generic.List[object]
:outer foreach ($f in $files) {
  $rel   = $f.FullName.Substring($RepoPath.Length + 1) -replace '\\', '/'
  $lines = @(Get-Content -LiteralPath $f.FullName -Encoding UTF8)
  for ($i = 0; $i -lt $lines.Count; $i += $ChunkLines) {
    if ($chunks.Count -ge $MaxChunks) { break outer }
    $end = [Math]::Min($i + $ChunkLines, $lines.Count) - 1
    $chunks.Add([pscustomobject]@{ Path = $rel; Start = $i + 1; End = $end + 1
                                   Text = "$rel`n" + ($lines[$i..$end] -join "`n") })
  }
}
$chunkArr = $chunks.ToArray()
Write-Host "Model: $Model | Java files: $($files.Count) | Chunks: $($chunkArr.Length)"

# 2. Warm-up (loads the model; excluded from timing)
$null = Invoke-Embed @('warm-up')

# 3. Timed embedding
$vectors = New-Object System.Collections.Generic.List[double[]]
$sw = [Diagnostics.Stopwatch]::StartNew()
for ($b = 0; $b -lt $chunkArr.Length; $b += $BatchSize) {
  $last  = [Math]::Min($b + $BatchSize, $chunkArr.Length) - 1
  $texts = @($chunkArr[$b..$last] | ForEach-Object { $DocPrefix + $_.Text })
  foreach ($e in (Invoke-Embed $texts)) { $vectors.Add([double[]]$e) }
}
$sw.Stop()
$msPerChunk = [Math]::Round($sw.Elapsed.TotalMilliseconds / $chunkArr.Length, 1)
Write-Host "Dimensions: $($vectors[0].Length)"
Write-Host ("Total: {0:N1} s | {1} ms/chunk | projected 5,000 chunks: {2:N1} min" -f `
            $sw.Elapsed.TotalSeconds, $msPerChunk, ($msPerChunk * 5000 / 60000))

# 4. Optional spot check: file-level Recall@5
if ($QueriesFile) {
  $queries = Get-Content -LiteralPath $QueriesFile -Raw -Encoding UTF8 | ConvertFrom-Json
  $hits = 0
  foreach ($q in $queries) {
    $qv = [double[]]((Invoke-Embed @($QueryPrefix + $q.q))[0])
    $scored = for ($i = 0; $i -lt $vectors.Count; $i++) {
      [pscustomobject]@{ Idx = $i; Score = (Get-Cosine $qv $vectors[$i]) }
    }
    $paths = @($scored | Sort-Object Score -Descending | Select-Object -First 5 |
               ForEach-Object { $chunkArr[$_.Idx].Path })
    $hit = @($paths | Where-Object { $_ -like "*$($q.expect)*" }).Count -gt 0
    if ($hit) { $hits++ }
    Write-Host ("[{0}] {1}" -f $(if ($hit) { 'HIT ' } else { 'MISS' }), $q.q)
    $paths | ForEach-Object { Write-Host "        $_" }
  }
  Write-Host "Recall@5 (file-level): $hits / $($queries.Count)"
}
