#!/usr/bin/env bash
# native-guardrails.sh — keep GrapMail pure-native (iOS: Swift/SwiftUI, Android: Kotlin/Compose).
# Fails when a cross-platform/hybrid framework artifact appears in the repository.
# Usage: bash native-guardrails.sh [repo-path] [ref]
# Exit: 0 = PASS, 1 = FAIL (guard violation), 2 = setup error.
set -u
REPO=${1:-.}; REF=${2:-HEAD}
cd "$REPO" 2>/dev/null || { echo "SETUP-FAIL: repo not found: $REPO"; exit 2; }
git rev-parse --verify "$REF" >/dev/null 2>&1 || { echo "SETUP-FAIL: bad ref: $REF"; exit 2; }

fails=0; warns=0
say(){ printf '%s\n' "$*"; }
fail(){ fails=$((fails+1)); say "FAIL: $*"; }
warn(){ warns=$((warns+1)); say "WARN: $*"; }
ok(){ say "PASS: $*"; }

files=$(git ls-tree -r --name-only "$REF") || { echo "SETUP-FAIL: ls-tree failed"; exit 2; }

# 1) no hybrid/cross-platform artifacts anywhere in the tree
m=$(printf '%s\n' "$files" | grep -iE 'pubspec\.ya?ml$|\.dart$|metro\.config\.|capacitor\.config|ionic\.config\.json|^ios/Podfile$|\.jsx$|\.tsx$' || true)
if [ -n "$m" ]; then fail "hybrid artifacts present:"; printf '%s\n' "$m" | head -20; else ok "no hybrid/cross-platform artifacts in the file tree"; fi

# 2) no hybrid framework tokens in app sources
h=$(git grep -l -I -E 'io\.flutter|FlutterActivity|FlutterEngine|facebook\.react|ReactRootView|react-native|Capacitor|Cordova' "$REF" -- ios android 2>/dev/null || true)
if [ -n "$h" ]; then fail "hybrid framework tokens in source:"; printf '%s\n' "$h"; else ok "no hybrid framework tokens in ios/ + android/ sources"; fi

# 3) WebView budget (allowlist; grow only with deliberate review)
wl_ios=5; wl_and=5
ios_wv=$(git grep -l -I -E 'WKWebView|import WebKit' "$REF" -- ios 2>/dev/null | wc -l)
and_wv=$(git grep -l -I -E 'android\.webkit\.WebView|WebView\(' "$REF" -- android 2>/dev/null | wc -l)
say "INFO: WebKit usage iOS=$ios_wv (budget $wl_ios) | WebView usage Android=$and_wv (budget $wl_and)"
[ "$ios_wv" -le "$wl_ios" ] || warn "iOS WebKit file count exceeds budget — review that the app shell stayed native"
[ "$and_wv" -le "$wl_and" ] || warn "Android WebView file count exceeds budget — review that the app shell stayed native"

# 4) native toolchain manifests present
printf '%s\n' "$files" | grep -q '^ios/project\.yml$' && ok "XcodeGen manifest present (ios/project.yml)" || fail "ios/project.yml missing"
printf '%s\n' "$files" | grep -q '^android/app/build\.gradle\.kts$' && ok "Gradle Kotlin DSL present (android/app/build.gradle.kts)" || fail "android Gradle Kotlin DSL missing"
printf '%s\n' "$files" | grep -q '\.swift$' && ok "Swift sources present" || fail "no Swift sources"
printf '%s\n' "$files" | grep -q '\.kt$' && ok "Kotlin sources present" || fail "no Kotlin sources"
printf '%s\n' "$files" | grep -q '\.java$' && warn "Java sources present — still native, but review (Kotlin is the project standard)" || ok "no Java sources"

say "----"
say "RESULT: fails=$fails warns=$warns"
if [ "$fails" -eq 0 ]; then say "NATIVE-GUARDRAIL: PASS"; exit 0; else say "NATIVE-GUARDRAIL: FAIL"; exit 1; fi
