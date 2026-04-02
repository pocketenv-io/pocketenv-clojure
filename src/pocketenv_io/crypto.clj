(ns pocketenv-io.crypto
  "Client-side encryption matching libsodium's crypto_box_seal (anonymous sealed box).

  Algorithm:
    1. Generate ephemeral Curve25519 (X25519) keypair
    2. Derive nonce = BLAKE2b-24(eph_pk || recipient_pk) — matches libsodium exactly
    3. Encrypt with NaCl crypto_box(message, nonce, eph_sk, recipient_pk)
    4. Output = eph_pk (32 bytes) || ciphertext
    5. Base64url-encode without padding

  The server's public key is resolved in order from:
    1. POCKETENV_PUBLIC_KEY environment variable
    2. The default production key"
  (:import [com.goterl.lazysodium LazySodiumJava SodiumJava]
           [java.util Base64]))

(def ^:private default-public-key-hex
  "2bf96e12d109e6948046a7803ef1696e12c11f04f20a6ce64dbd4bcd93db9341")

(defonce ^:private ^LazySodiumJava sodium
  (LazySodiumJava. (SodiumJava.)))

;; crypto_box_SEALBYTES = crypto_box_PUBLICKEYBYTES (32) + crypto_box_MACBYTES (16)
(def ^:private seal-overhead 48)

(defn- hex->bytes [hex]
  (byte-array
    (for [i (range 0 (count hex) 2)]
      (unchecked-byte (Integer/parseInt (subs hex i (+ i 2)) 16)))))

(defn- public-key []
  (hex->bytes (or (System/getenv "POCKETENV_PUBLIC_KEY")
                  default-public-key-hex)))

(defn encrypt
  "Encrypts plaintext using the server's public key via crypto_box_seal.
   Returns base64url-encoded ciphertext without padding."
  [^String plaintext]
  (let [pk       (public-key)
        msg      (.getBytes plaintext "UTF-8")
        cipher   (byte-array (+ (alength msg) seal-overhead))
        ok?      (.cryptoBoxSeal sodium cipher msg (alength msg) pk)]
    (when-not ok?
      (throw (ex-info "crypto_box_seal failed" {})))
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) cipher)))
