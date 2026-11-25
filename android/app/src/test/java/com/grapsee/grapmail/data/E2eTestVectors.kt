package com.grapsee.grapmail.data

/**
 * Fixed end-to-end vectors shared byte-for-byte with
 * ios/GrapMailTests/E2eTestVectors.swift and testvectors/e2e-vectors.json.
 *
 * Produced by testvectors/gen_e2e_vectors.py with OpenSSL (Python
 * `cryptography`) from fixed seeds, following services/mail-service/src/e2e/
 * envelope.ts exactly: X25519 -> HKDF-SHA256 (32 zero-byte salt,
 * "grapmail-e2e-v1" / "grapmail-e2e-v2-kek") -> AES-256-GCM with the frozen
 * AAD strings, Ed25519 over the frozen signed-bytes strings, and
 * PBKDF2-HMAC-SHA256 over the UTF-8 passphrase. A third implementation
 * opening them is the cross-platform compatibility proof. Never regenerate
 * them to make a failing test pass: a change here is a wire-format change.
 */
object E2eTestVectors {
    const val RECIPIENT_PUBLIC = "MCowBQYDK2VuAyEAJxF4uKWdtYPwZ9XKlHusQEW/tFrrfRpxac9W5g8Y/Ec="
    const val RECIPIENT_PRIVATE = "MC4CAQAwBQYDK2VuBCIEIIy/0MJsYDxmn0nwGjS/c4PVY4+zyTSRyf+xIh2cDK3O"
    const val OTHER_PUBLIC = "MCowBQYDK2VuAyEA0gWbC1w9Ayn8tb5rsJFC3KR3Y2MCAJHIMijSxAasrCE="
    const val OTHER_PRIVATE = "MC4CAQAwBQYDK2VuBCIEIK2mj7dzCDsARSHir0f8buQRuzbq0EOUKhqrncXGcIPr"
    const val SIGNER_PUBLIC = "MCowBQYDK2VwAyEAjsJTTB5gUDIu+4svatZTEj8SHZ6G9755ab3L6ITaLyA="
    const val SIGNER_PRIVATE = "MC4CAQAwBQYDK2VwBCIEIEI3DYv7D09kFURwGsCD8HLH+eW2XUFVQsLPSNCmKcXx"
    const val PLAINTEXT = "Meet at 09:30 — café ☕ at the north gate.\nSecond line, ünïcødé."
    const val RECIPIENT_FINGERPRINT = "0C:65:58:3F:B5:B9:3D:31:97:B3:A7:A5:F7:40:6F:7C:65:DB:7B:58:A7:5F:21:26:98:A6:6C:A8:D8:6A:DA:B6"

    /** v1, one recipient (RECIPIENT), signed by SIGNER. */
    const val ENVELOPE_V1 = "{\"v\":1,\"alg\":\"X25519+HKDF-SHA256+AES-256-GCM\",\"epk\":\"MCowBQYDK2VuAyEAQAUZ9qnj6QT/YXbzowoJm5MNENt2jOgKEqha+3ULSlE=\",\"iv\":\"6Lox7hxAWS/tLpZY\",\"ct\":\"2+iVe12EvtIOFpIe4mhyL3SDf2jAYI2c00bRG8E6bUldp0/Gx1afvkm5JqZq+EWDQ9WdXJwgp1CtwgAUJlNsuyBN04NF/vw/\",\"tag\":\"rHrOcpXV7eN1so5w28Z43Q==\",\"sig\":\"Nq63X5RYkj34URHU0T+0Yu4G9Zryqfu4zeRAgzk6KEV2oicSNYEirKkhacxatw1L0+Lni+VJkfhu7/o5pFnsCQ==\",\"signerPub\":\"MCowBQYDK2VwAyEAjsJTTB5gUDIu+4svatZTEj8SHZ6G9755ab3L6ITaLyA=\"}"

    /** v2, recipients [OTHER, RECIPIENT] in that order, signed by SIGNER. */
    const val ENVELOPE_V2 = "{\"v\":2,\"alg\":\"X25519+HKDF-SHA256+AES-256-GCM (multi-recipient)\",\"iv\":\"h/5SHWcWe1jn2dlK\",\"ct\":\"4bh7ZbufSca8zpPEHgEYpIl3fgNGuRuopiOzuEV99pdfSTM8RnDffbkZ/u4z+gi07xRCVk0Cf2T2dx3GVcXQYK39wKKYzWvh\",\"tag\":\"Q6p/Pf03NCmANfvKH5TaeQ==\",\"recipients\":[{\"epk\":\"MCowBQYDK2VuAyEAkq6pT57HIkluJ4oSjQatO138aqNxLJguWS+cMBAnkH8=\",\"wrapIv\":\"rF+YZ0AT1leK0RP4\",\"wrappedKey\":\"zjFPc9yX/5ZfnopPRNZ+t+8rDSd4dK1YzcCReXxsJpQ=\",\"wrapTag\":\"PgLxhhmFHUEQlOB3voyjjg==\"},{\"epk\":\"MCowBQYDK2VuAyEAolusevGBjdl0o/vArcP3/EkuhCcqk6DjAQwz7KdtmXg=\",\"wrapIv\":\"LxAzwnJyPz4pktjv\",\"wrappedKey\":\"0/Tydn3vfEwTiZ8VvKG1vAax6eQDgLAxYClK+615Tz4=\",\"wrapTag\":\"e2M4rvNF1lirKQkrajvLzw==\"}],\"sig\":\"KA43RJLTUor+0iH62PF/q3pamYoT/7UbOM7y26vpD4tAdCdg+TvGq0iRS8EbCdi+9ZoehA1+5PcSKwNIWINjAg==\",\"signerPub\":\"MCowBQYDK2VwAyEAjsJTTB5gUDIu+4svatZTEj8SHZ6G9755ab3L6ITaLyA=\"}"

    /** RECIPIENT_PRIVATE under PROTECTED_PASSPHRASE, 1000 PBKDF2 rounds. */
    const val PROTECTED_PASSPHRASE = "correct horse — ключ 🔑"
    const val PROTECTED_SALT = "Ffxvy834du55iqBOId1aGA=="
    const val PROTECTED_IV = "5iAcRpOrfsw8LGSC"
    const val PROTECTED_CT = "IctAcL868DewjHywRJbR8ebtN8p/1xcpH5Y4I0iKeI+BOdJHB6DkxJ4QxFvLV+sluZIZnjDmV9NZMjOIdtuOeQ=="
    const val PROTECTED_ITERATIONS = 1000

    /** A recovery kit: both private keys under the canonical recovery key, 1000 rounds. */
    const val RECOVERY_KEY_TYPED = "7k3m 9qxz lOab cdef gh1j kmnp qrst"
    const val RECOVERY_KEY_CANONICAL = "7K3M9QXZ10ABCDEFGH1JKMNPQRST"
    const val RECOVERY_KIT = "{\"format\":\"grapmail-e2e-recovery-kit/1\",\"createdAt\":\"2026-11-23T00:00:00Z\",\"encryption\":{\"format\":\"grapmail-e2e-protected-key/1\",\"kind\":\"X25519\",\"publicKey\":\"MCowBQYDK2VuAyEAJxF4uKWdtYPwZ9XKlHusQEW/tFrrfRpxac9W5g8Y/Ec=\",\"kdf\":\"PBKDF2-SHA256\",\"iterations\":1000,\"salt\":\"CALv2rTyF1P5UmysPxCFeg==\",\"iv\":\"KlePp41wrl+5AHk9\",\"ct\":\"XDwGg008tz4V2miaEKRBZ7CtUXlewR3Jbe5MQxJ7zxD+b7zwrjQvJlg9IrMvgN9I648fFQYRQv1UeruWW+MI9Q==\"},\"signing\":{\"format\":\"grapmail-e2e-protected-key/1\",\"kind\":\"Ed25519\",\"publicKey\":\"MCowBQYDK2VwAyEAjsJTTB5gUDIu+4svatZTEj8SHZ6G9755ab3L6ITaLyA=\",\"kdf\":\"PBKDF2-SHA256\",\"iterations\":1000,\"salt\":\"iV59eBwkqphHFTIiEP4mCw==\",\"iv\":\"d+Ypcj4d/IRwfld0\",\"ct\":\"WX93RwUWI+GcP9aAj8XSiXPzrt8p6Qauf3lVMZTf+eS2gjmCc0FJCLlBuVmaJxmqFEWzaVBbFQwyT1V4zKha0w==\"}}"
}
