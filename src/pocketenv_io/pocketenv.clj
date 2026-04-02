(ns pocketenv-io.pocketenv
  "Clojure SDK for the Pocketenv sandbox platform.

  This namespace is the main entry point for the SDK. It returns Sandbox
  records that you pipe operations on using Clojure's threading macro:

    (require '[pocketenv-io.pocketenv :as pocketenv]
             '[pocketenv-io.sandbox :as sandbox])

    (-> (pocketenv/create-sandbox \"my-box\")
        (sandbox/start)
        (sandbox/wait-until-running)
        (sandbox/exec \"clojure\" [\"-M:test\"]))

  ## Configuration

  Set any of the following environment variables:

    POCKETENV_TOKEN      — bearer token
    POCKETENV_API_URL    — API base URL (default: https://api.pocketenv.io)
    POCKETENV_PUBLIC_KEY — server public key (hex-encoded)

  Or place a token in ~/.pocketenv/token.json: {\"token\": \"...\"}

  ## Return values

  All functions return {:ok value} on success or {:error reason} on failure.
  Sandbox operation functions also accept {:ok sandbox} as their first argument
  so you can chain them with the -> threading macro without unwrapping."
  (:require [pocketenv-io.api :as api]))

;; ---------------------------------------------------------------------------
;; Sandboxes
;; ---------------------------------------------------------------------------

(defn create-sandbox
  "Creates a new sandbox. Returns {:ok Sandbox}.

  Options:
    :base       — AT-URI of the base image (default: openclaw)
    :provider   — \"cloudflare\" (default), \"daytona\", \"deno\", \"vercel\", \"sprites\"
    :repo       — GitHub repo URL to clone on start
    :keep-alive — keep sandbox alive after session ends
    :token      — bearer token override"
  ([name] (create-sandbox name {}))
  ([name opts] (api/create-sandbox name opts)))

(defn get-sandbox
  "Fetches a single sandbox by id or name. Returns {:ok Sandbox} or {:ok nil}.

  Options: :token"
  ([id] (get-sandbox id {}))
  ([id opts] (api/get-sandbox id opts)))

(defn list-sandboxes
  "Lists the official public sandbox catalog.

  Returns {:ok {:sandboxes [...] :total N}}.

  Options: :limit (30), :offset (0), :token"
  ([] (list-sandboxes {}))
  ([opts] (api/list-sandboxes opts)))

(defn list-sandboxes-by-actor
  "Lists all sandboxes belonging to a specific actor (DID or handle).

  Returns {:ok {:sandboxes [...] :total N}}.

  Options: :limit (30), :offset (0), :token"
  ([did] (list-sandboxes-by-actor did {}))
  ([did opts] (api/list-sandboxes-by-actor did opts)))

;; ---------------------------------------------------------------------------
;; Actor / profile
;; ---------------------------------------------------------------------------

(defn me
  "Fetches the profile of the currently authenticated user.

  Returns {:ok Profile}."
  ([] (me {}))
  ([opts] (api/me opts)))

(defn get-profile
  "Fetches the profile of any actor by DID or handle.

  Returns {:ok Profile}."
  ([did] (get-profile did {}))
  ([did opts] (api/get-profile did opts)))

;; ---------------------------------------------------------------------------
;; Secrets
;; ---------------------------------------------------------------------------

(defn list-secrets
  "Lists all secrets for a sandbox.

  Returns {:ok [Secret]}.

  Options: :limit (100), :offset (0), :token"
  ([sandbox-id] (list-secrets sandbox-id {}))
  ([sandbox-id opts] (api/list-secrets sandbox-id opts)))

(defn add-secret
  "Adds an encrypted secret to a sandbox. Value is encrypted client-side.

  Returns {:ok response}.

  Options: :token"
  ([sandbox-id name value] (add-secret sandbox-id name value {}))
  ([sandbox-id name value opts] (api/add-secret sandbox-id name value opts)))

(defn delete-secret
  "Deletes a secret by its id.

  Returns {:ok response}.

  Options: :token"
  ([id] (delete-secret id {}))
  ([id opts] (api/delete-secret id opts)))

;; ---------------------------------------------------------------------------
;; SSH Keys
;; ---------------------------------------------------------------------------

(defn get-ssh-keys
  "Fetches the SSH key pair for a sandbox.

  Returns {:ok SshKey}.

  Options: :token"
  ([sandbox-id] (get-ssh-keys sandbox-id {}))
  ([sandbox-id opts] (api/get-ssh-keys sandbox-id opts)))

(defn put-ssh-keys
  "Stores an SSH key pair. The private key is encrypted client-side.

  Options: :token"
  ([sandbox-id private-key public-key] (put-ssh-keys sandbox-id private-key public-key {}))
  ([sandbox-id private-key public-key opts] (api/put-ssh-keys sandbox-id private-key public-key opts)))

;; ---------------------------------------------------------------------------
;; Tailscale
;; ---------------------------------------------------------------------------

(defn get-tailscale-auth-key
  "Fetches the Tailscale auth key for a sandbox.

  Returns {:ok TailscaleAuthKey}.

  Options: :token"
  ([sandbox-id] (get-tailscale-auth-key sandbox-id {}))
  ([sandbox-id opts] (api/get-tailscale-auth-key sandbox-id opts)))

(defn put-tailscale-auth-key
  "Stores a Tailscale auth key. Must start with \"tskey-auth-\".
   Encrypted client-side before transmission.

  Options: :token"
  ([sandbox-id auth-key] (put-tailscale-auth-key sandbox-id auth-key {}))
  ([sandbox-id auth-key opts] (api/put-tailscale-auth-key sandbox-id auth-key opts)))
