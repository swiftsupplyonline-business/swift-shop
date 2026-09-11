$email = "test5@test.com"
foreach ($char in $email.ToCharArray()) {
    if ($char -eq '@') {
        adb.exe shell input keyevent 77 # @ usually 77 or text
    } else {
        adb.exe shell input text $char
    }
    Start-Sleep -m 100
}
