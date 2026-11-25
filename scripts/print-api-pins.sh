#!/usr/bin/env bash
# Print the certificate pins for the GrapMail API host, for both apps.
#
#   scripts/print-api-pins.sh [host]        (default: grapsee.com)
#
# Reads the certificate chain the server presents right now and prints the
# SHA-256 of each certificate's public key (SPKI), which is what OkHttp's
# CertificatePinner and the iOS PinnedSessionDelegate compare. Needs only openssl.
#
# The pins themselves live in security/api-pins.json (one file for both apps;
# the "sha256" values there are these hashes WITHOUT the "sha256/" prefix).
# scripts/check-api-pins.py checks that file against the live chains and its
# expiry; run it after every edit, then python3 scripts/generate-pins.py.
#
# Which to pin (OWASP MASTG-TEST-0022 / 0068):
#   - pin the INTERMEDIATE (issuing CA) key, not the leaf: the leaf changes every
#     renewal, the issuing CA key does not;
#   - always pin a BACKUP as well (the root below it, or the key of a second CA you
#     could move to). The Android build refuses to pin fewer than two keys.
#   - re-run this before every CA change and ship the new pins first.
set -euo pipefail
host="${1:-grapsee.com}"
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
openssl s_client -connect "$host:443" -servername "$host" -showcerts </dev/null 2>/dev/null \
  | awk -v dir="$tmp" '/BEGIN CERTIFICATE/{n++; f=dir "/cert" n ".pem"} f{print > f} /END CERTIFICATE/{f=""}'

count=$(ls "$tmp"/cert*.pem 2>/dev/null | wc -l | tr -d ' ')
[ "$count" -gt 0 ] || { echo "No certificates were read from $host:443" >&2; exit 1; }

echo "Certificate chain presented by $host (0 = leaf):"
i=0
pins=()
for pem in $(ls "$tmp"/cert*.pem | sort -V); do
  subject="$(openssl x509 -in "$pem" -noout -subject | sed 's/^subject= *//')"
  expires="$(openssl x509 -in "$pem" -noout -enddate | sed 's/^notAfter=//')"
  pin="$(openssl x509 -in "$pem" -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl base64 -A)"
  echo "  [$i] $subject"
  echo "      expires: $expires"
  echo "      sha256/$pin"
  [ "$i" -gt 0 ] && pins+=("sha256/$pin")
  i=$((i + 1))
done

echo
if [ "${#pins[@]}" -lt 2 ]; then
  echo "The server sends only ${#pins[@]} CA certificate(s). Add a backup pin by hand: the key of the"
  echo "root above the intermediate, or of a second CA you could switch to. Do not ship one pin."
fi
joined="$(IFS=,; echo "${pins[*]}")"
echo "Both apps: put the CA keys into security/api-pins.json (pins[].sha256, without"
echo "the sha256/ prefix; keep the old ones until old installs have updated), then:"
echo "  python3 scripts/generate-pins.py && python3 scripts/check-api-pins.py"
echo
echo "Android one-off override of the JSON (CA keys from this chain):"
echo "  ./gradlew assembleRelease -PgrapmailApiPins=\"$joined\""
echo "  or GRAPMAIL_API_PINS=\"$joined\" in the release workflow's environment"
echo
echo "iOS: do NOT add NSPinnedDomains to ios/project.yml. ATS pins never expire, so"
echo "a stale set locks old installs out for good; PinnedSessionDelegate.swift reads"
echo "the same JSON (via ApiPins.swift) and honours its expiry."
