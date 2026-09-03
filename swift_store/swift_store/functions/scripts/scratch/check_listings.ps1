$token = (firebase auth:print-access-token).Trim().Replace("`r", "").Replace("`n", "")
$headers = @{ "Authorization" = "Bearer $token" }
try {
    $resp = Invoke-RestMethod -Uri "https://firestore.googleapis.com/v1/projects/swift-dev-3d3ae/databases/(default)/documents/listings" -Headers $headers
    $resp.documents | ForEach-Object { Write-Output $_.name }
} catch {
    Write-Output $_.Exception.Message
}
