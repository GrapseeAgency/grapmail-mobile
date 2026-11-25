#!/usr/bin/env python3
"""Regenerate testvectors/e2e-vectors.json (and print the values used by
android/.../data/E2eTestVectors.kt and ios/GrapMailTests/E2eTestVectors.swift).

DO NOT run this to "fix" a failing E2E test. The vectors are a frozen
wire-format contract: everything is derived from fixed seeds, so re-running it
reproduces byte-identical output. If the output ever changes, the FORMAT
changed, and every envelope already stored would stop opening.

Independent implementation (OpenSSL via the `cryptography` package) of
services/mail-service/src/e2e/envelope.ts:
  v1: X25519(eph, recipient) -> HKDF-SHA256(salt=32 zero bytes, info="grapmail-e2e-v1")
      -> AES-256-GCM, AAD "1\\0<epk>\\0<iv>", sig = Ed25519("<epk>.<iv>.<ct>.<tag>")
  v2: content key wrapped per recipient with info "grapmail-e2e-v2-kek",
      wrap AAD "2\\0<wrapIv>\\0<epk>\\0<iv>", content AAD "2\\0<iv>\\0<epk1,epk2,...>",
      sig = Ed25519("2.<iv>.<ct>.<tag>.<epk:wrapIv:wrappedKey:wrapTag,...>")
  protected key: PBKDF2-HMAC-SHA256(UTF-8 passphrase, salt, iterations, 32)
      -> AES-256-GCM(ct||tag), AAD "grapmail-e2e-protected-key/1\\0<kind>\\0<publicKey>"

    pip install cryptography && python3 testvectors/gen_e2e_vectors.py
"""
import base64
import hashlib
import json
import pathlib

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives import serialization as S
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


def sh(s): return hashlib.sha256(s.encode()).digest()
def b64(b): return base64.b64encode(b).decode()
def xpriv(seed): return X25519PrivateKey.from_private_bytes(sh(seed))
def spki(pub): return pub.public_bytes(S.Encoding.DER, S.PublicFormat.SubjectPublicKeyInfo)
def pkcs8(priv): return priv.private_bytes(S.Encoding.DER, S.PrivateFormat.PKCS8, S.NoEncryption())
def hk(shared, info): return HKDF(hashes.SHA256(), 32, b"\x00" * 32, info).derive(shared)


rec, oth = xpriv("grapmail-test-recipient"), xpriv("grapmail-test-other")
signer = Ed25519PrivateKey.from_private_bytes(sh("grapmail-test-signer"))
REC_PUB, REC_PRIV = b64(spki(rec.public_key())), b64(pkcs8(rec))
OTH_PUB, OTH_PRIV = b64(spki(oth.public_key())), b64(pkcs8(oth))
SIG_PUB, SIG_PRIV = b64(spki(signer.public_key())), b64(pkcs8(signer))
PT = "Meet at 09:30 — café ☕ at the north gate.\nSecond line, ünïcødé."
ptb = PT.encode()

# v1
eph = xpriv("grapmail-test-ephemeral-v1")
epk = b64(spki(eph.public_key()))
iv = sh("iv-v1")[:12]
ivb = b64(iv)
sealed = AESGCM(hk(eph.exchange(rec.public_key()), b"grapmail-e2e-v1")).encrypt(iv, ptb, f"1\x00{epk}\x00{ivb}".encode())
ct, tag = b64(sealed[:-16]), b64(sealed[-16:])
V1 = {"v": 1, "alg": "X25519+HKDF-SHA256+AES-256-GCM", "epk": epk, "iv": ivb, "ct": ct, "tag": tag,
      "sig": b64(signer.sign(f"{epk}.{ivb}.{ct}.{tag}".encode())), "signerPub": SIG_PUB}

# v2: [other, recipient]
ck, civ = sh("content-key"), sh("iv-v2")[:12]
civb = b64(civ)
wraps = []
for i, r in enumerate([oth, rec]):
    e = xpriv(f"grapmail-test-ephemeral-v2-{i}")
    ek = b64(spki(e.public_key()))
    wiv = sh(f"wrap-iv-{i}")[:12]
    wivb = b64(wiv)
    w = AESGCM(hk(e.exchange(r.public_key()), b"grapmail-e2e-v2-kek")).encrypt(wiv, ck, f"2\x00{wivb}\x00{ek}\x00{civb}".encode())
    wraps.append({"epk": ek, "wrapIv": wivb, "wrappedKey": b64(w[:-16]), "wrapTag": b64(w[-16:])})
c = AESGCM(ck).encrypt(civ, ptb, f"2\x00{civb}\x00{','.join(w['epk'] for w in wraps)}".encode())
ct2, tag2 = b64(c[:-16]), b64(c[-16:])
wr = ",".join(f"{w['epk']}:{w['wrapIv']}:{w['wrappedKey']}:{w['wrapTag']}" for w in wraps)
V2 = {"v": 2, "alg": "X25519+HKDF-SHA256+AES-256-GCM (multi-recipient)", "iv": civb, "ct": ct2, "tag": tag2,
      "recipients": wraps, "sig": b64(signer.sign(f"2.{civb}.{ct2}.{tag2}.{wr}".encode())), "signerPub": SIG_PUB}

FP = ":".join(f"{b:02X}" for b in hashlib.sha256(base64.b64decode(REC_PUB)).digest())


def protect(priv_b64, pw, kind, pub, salt_seed, iv_seed, it=1000):
    salt, piv = sh(salt_seed)[:16], sh(iv_seed)[:12]
    kek = hashlib.pbkdf2_hmac("sha256", pw.encode("utf-8"), salt, it, 32)
    ctp = AESGCM(kek).encrypt(piv, base64.b64decode(priv_b64), f"grapmail-e2e-protected-key/1\x00{kind}\x00{pub}".encode())
    return {"format": "grapmail-e2e-protected-key/1", "kind": kind, "publicKey": pub, "kdf": "PBKDF2-SHA256",
            "iterations": it, "salt": b64(salt), "iv": b64(piv), "ct": b64(ctp)}


PASS = "correct horse — ключ 🔑"
RK_CANON = "7K3M9QXZ10ABCDEFGH1JKMNPQRST"
RK_TYPED = "7k3m 9qxz lOab cdef gh1j kmnp qrst"  # l->1, O->0, lower case, spaces
vectors = {
    "recipientPublicKey": REC_PUB, "recipientPrivateKey": REC_PRIV,
    "otherPublicKey": OTH_PUB, "otherPrivateKey": OTH_PRIV,
    "signerPublicKey": SIG_PUB, "signerPrivateKey": SIG_PRIV,
    "plaintext": PT, "recipientFingerprint": FP,
    "envelopeV1": V1, "envelopeV2": V2,
    "protectedKey": {"passphrase": PASS, "record": protect(REC_PRIV, PASS, "X25519", REC_PUB, "salt-prot", "iv-prot")},
    "recoveryKit": {
        "recoveryKeyTyped": RK_TYPED, "recoveryKeyCanonical": RK_CANON,
        "kit": {"format": "grapmail-e2e-recovery-kit/1", "createdAt": "2026-11-23T00:00:00Z",
                "encryption": protect(REC_PRIV, RK_CANON, "X25519", REC_PUB, "salt-kit-enc", "iv-kit-enc"),
                "signing": protect(SIG_PRIV, RK_CANON, "Ed25519", SIG_PUB, "salt-kit-sig", "iv-kit-sig")},
    },
}
out = pathlib.Path(__file__).with_name("e2e-vectors.json")
text = json.dumps(vectors, ensure_ascii=False, indent=2)
if out.exists() and out.read_text(encoding="utf-8") != text:
    raise SystemExit("e2e-vectors.json differs from the regenerated vectors: the wire format changed. Not overwriting.")
out.write_text(text, encoding="utf-8")
print(f"{out}: up to date")
