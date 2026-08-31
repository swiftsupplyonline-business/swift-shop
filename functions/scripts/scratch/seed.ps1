$token = (firebase auth:print-access-token).Trim()
$headers = @{
    "Authorization" = "Bearer $token"
    "Content-Type"  = "application/json"
}
$body = @{
    fields = @{
        title           = @{ stringValue = "Available Product" }
        priceMinorUnits = @{ integerValue = "10000" }
        priceCurrency   = @{ stringValue = "LSL" }
        isAvailable     = @{ booleanValue = $true }
        shopId          = @{ stringValue = "test_shop" }
        sellerId        = @{ stringValue = "test_seller" }
        createdAt       = @{ integerValue = "1724510000000" }
        description     = @{ stringValue = "Test product for Phase 5" }
        listingType     = @{ stringValue = "BUY" }
        imageUrls       = @{ arrayValue = @{ values = @() } }
        tags            = @{ arrayValue = @{ values = @() } }
    }
} | ConvertTo-Json -Depth 10

$resp = Invoke-RestMethod -Uri "https://firestore.googleapis.com/v1/projects/swift-dev-3d3ae/databases/(default)/documents/listings" -Method Post -Headers $headers -Body $body
Write-Output "Seeded: $($resp.name)"
