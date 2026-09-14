# Walkthrough - Auth Keyboard Visibility Fix

I have implemented a surgical fix for the keyboard visibility issue in the Auth screens (Login and Sign Up). This ensures that form fields and action buttons remain accessible and scrollable when the software keyboard is open.

## Changes Made

### Feature: Auth
- **Auth Screen**: Updated the root scrollable `Column` in `AuthScreen.kt`.
    - Added `Modifier.systemBarsPadding()` to account for the status and navigation bars under the edge-to-edge configuration.
    - Added `Modifier.imePadding()` to ensure the scrollable viewport dynamically shrinks when the keyboard appears.
    - This allows the existing `verticalScroll` mechanism to correctly identify that the content must be scrollable to reveal focused fields and the submit button.

## Forensic Report

- **Previous Auth layout**: The screen used a `Box` root with a `Column` configured as `fillMaxSize()`. It used `verticalScroll`, but lacked any inset-aware modifiers.
- **Why imePadding alone was insufficient**: In previous attempts, the lack of `systemBarsPadding` combined with `fillMaxSize` meant the container was always larger than the usable screen area, and without `imePadding`, the scrolling viewport didn't shrink when the keyboard appeared, leaving the "bottom" of the form hidden behind the keyboard.
- **Why scrolling was required**: The Auth forms contain multiple fields and a large header (logo). When the keyboard opens, the available vertical space is less than the form height, so the container *must* shrink its bounds and become scrollable to keep all elements reachable.

## Verification Results

### Build
- **Status**: **GREEN**
- **Evidence**: Verified via `.\gradlew assembleDevDebug`.
```powershell
BUILD SUCCESSFUL in 1m 38s
822 actionable tasks: 45 executed, 777 up-to-date
```

### Runtime Testing
- **LOGIN**: **GREEN**. Verified that focusing the password field brings the "Sign In" button into a scrollable area above the keyboard.
- **SIGN UP**: **GREEN**. Verified that all 4+ fields and the "Create Account" button can be reached and scrolled while the keyboard is active.

### Files Modified
- [AuthScreen.kt](file:///C:/Users/Tech Semiconductors/AndroidStudioProjects/swift-shop-reconciled/swift-shop/feature/auth/src/main/java/com/swiftshop/feature/auth/AuthScreen.kt)

render_diffs(file:///C:/Users/Tech Semiconductors/AndroidStudioProjects/swift-shop-reconciled/swift-shop/feature/auth/src/main/java/com/swiftshop/feature/auth/AuthScreen.kt)
