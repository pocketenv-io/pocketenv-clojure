(ns pocketenv-io.api
  "Internal HTTP layer. Not part of the public API.
   Consumers should use pocketenv-io.pocketenv and pocketenv-io.sandbox."
  (:require [pocketenv-io.client :as client]
            [pocketenv-io.crypto :as crypto]
            [pocketenv-io.sandbox :as types]
            [clojure.string :as str]))

(def ^:private default-base
  "at://did:plc:aturpi2ls3yvsmhc6wybomun/io.pocketenv.sandbox/openclaw")

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- maybe-assoc [m k v]
  (if (some? v) (assoc m k v) m))

(defn- redact-tailscale-key [auth-key]
  (if (> (count auth-key) 14)
    (str (subs auth-key 0 11)
         (apply str (repeat (- (count auth-key) 14) "*"))
         (subs auth-key (- (count auth-key) 3)))
    auth-key))

(defn- redact-ssh-private-key [private-key]
  (let [header "-----BEGIN OPENSSH PRIVATE KEY-----"
        footer "-----END OPENSSH PRIVATE KEY-----"]
    (if (and (str/includes? private-key header)
             (str/includes? private-key footer))
      (let [header-end    (.indexOf private-key header)
            body-start    (+ header-end (count header))
            footer-start  (.indexOf private-key footer)
            body          (subs private-key body-start footer-start)
            chars         (vec body)
            non-nl-idxs   (->> (map-indexed vector chars)
                               (remove (fn [[_ c]] (= c \newline)))
                               (mapv first))
            masked-chars  (if (> (count non-nl-idxs) 15)
                            (let [n           (count non-nl-idxs)
                                  middle      (set (take (- n 15) (drop 10 non-nl-idxs)))]
                              (map-indexed (fn [i c] (if (middle i) \* c)) chars))
                            chars)
            masked-body   (apply str masked-chars)]
        (-> (str header masked-body footer)
            (str/replace "\n" "\\n")))
      (str/replace private-key "\n" "\\n"))))

;; ---------------------------------------------------------------------------
;; Sandbox CRUD
;; ---------------------------------------------------------------------------

(defn create-sandbox
  "Creates a new sandbox. Options: :base, :provider, :repo, :keep-alive, :token"
  [name opts]
  (let [body (-> {"name"     name
                  "base"     (get opts :base default-base)
                  "provider" (str (get opts :provider "cloudflare"))}
                 (maybe-assoc "repo"      (:repo opts))
                 (maybe-assoc "keepAlive" (:keep-alive opts)))
        r    (client/post "/xrpc/io.pocketenv.sandbox.createSandbox" body opts)]
    (if-let [data (:ok r)]
      {:ok (types/sandbox-from-map data)}
      r)))

(defn start-sandbox
  "Starts a sandbox by name/id. Options: :repo, :keep-alive, :token"
  [id opts]
  (let [body (-> {}
                 (maybe-assoc "repo"      (:repo opts))
                 (maybe-assoc "keepAlive" (:keep-alive opts)))]
    (client/post "/xrpc/io.pocketenv.sandbox.startSandbox"
                 body
                 (assoc opts :params {"id" id}))))

(defn stop-sandbox
  "Stops a sandbox by name/id. Options: :token"
  [id opts]
  (client/post "/xrpc/io.pocketenv.sandbox.stopSandbox"
               nil
               (assoc opts :params {"id" id})))

(defn delete-sandbox
  "Deletes a sandbox by name/id. Options: :token"
  [id opts]
  (client/post "/xrpc/io.pocketenv.sandbox.deleteSandbox"
               nil
               (assoc opts :params {"id" id})))

;; ---------------------------------------------------------------------------
;; Sandbox queries
;; ---------------------------------------------------------------------------

(defn get-sandbox
  "Fetches a single sandbox by id or name. Returns {:ok sandbox} or {:ok nil}."
  [id opts]
  (let [r (client/get "/xrpc/io.pocketenv.sandbox.getSandbox"
                      (assoc opts :params {"id" id}))]
    (if-let [data (:ok r)]
      (let [sb (or (get data "sandbox") data)]
        {:ok (when sb (types/sandbox-from-map sb))})
      r)))

(defn list-sandboxes
  "Lists the public sandbox catalog. Options: :limit (30), :offset (0), :token"
  [opts]
  (let [r (client/get "/xrpc/io.pocketenv.sandbox.getSandboxes"
                      (assoc opts :params {"limit"  (get opts :limit 30)
                                           "offset" (get opts :offset 0)}))]
    (if-let [data (:ok r)]
      {:ok {:sandboxes (mapv types/sandbox-from-map (get data "sandboxes"))
            :total     (get data "total")}}
      r)))

(defn list-sandboxes-by-actor
  "Lists sandboxes for a given actor (DID or handle). Options: :limit, :offset, :token"
  [did opts]
  (let [r (client/get "/xrpc/io.pocketenv.actor.getActorSandboxes"
                      (assoc opts :params {"did"    did
                                           "limit"  (get opts :limit 30)
                                           "offset" (get opts :offset 0)}))]
    (if-let [data (:ok r)]
      {:ok {:sandboxes (mapv types/sandbox-from-map (get data "sandboxes"))
            :total     (get data "total")}}
      r)))

(defn wait-until-running
  "Polls until the sandbox is :running or timeout is reached.

  Options: :timeout-ms (60000), :interval-ms (2000), :token"
  [id opts]
  (let [timeout-ms  (get opts :timeout-ms 60000)
        interval-ms (get opts :interval-ms 2000)
        deadline    (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if (>= (System/currentTimeMillis) deadline)
        {:error :timeout}
        (let [r (get-sandbox id opts)]
          (cond
            (and (:ok r) (= :running (:status (:ok r)))) r
            (:ok r) (do (Thread/sleep interval-ms) (recur))
            :else r))))))

;; ---------------------------------------------------------------------------
;; Exec
;; ---------------------------------------------------------------------------

(defn exec
  "Executes a shell command in the sandbox. Returns {:ok ExecResult}."
  [id cmd args opts]
  (let [command (str/join " " (cons cmd args))
        r       (client/post "/xrpc/io.pocketenv.sandbox.exec"
                             {"command" command}
                             (assoc opts :params {"id" id}))]
    (if-let [data (:ok r)]
      {:ok (types/exec-result-from-map data)}
      r)))

;; ---------------------------------------------------------------------------
;; Ports
;; ---------------------------------------------------------------------------

(defn expose-port
  "Exposes a port on the sandbox. Returns {:ok preview-url-or-nil}."
  [id port opts]
  (let [body (-> {"port" port}
                 (maybe-assoc "description" (:description opts)))
        r    (client/post "/xrpc/io.pocketenv.sandbox.exposePort"
                          body
                          (assoc opts :params {"id" id}))]
    (if-let [data (:ok r)]
      {:ok (get data "previewUrl")}
      r)))

(defn unexpose-port
  "Removes an exposed port from the sandbox."
  [id port opts]
  (client/post "/xrpc/io.pocketenv.sandbox.unexposePort"
               {"port" port}
               (assoc opts :params {"id" id})))

(defn list-ports
  "Lists all exposed ports on the sandbox. Returns {:ok [Port]}."
  [id opts]
  (let [r (client/get "/xrpc/io.pocketenv.sandbox.getExposedPorts"
                      (assoc opts :params {"id" id}))]
    (if-let [data (:ok r)]
      {:ok (mapv types/port-from-map (get data "ports"))}
      r)))

;; ---------------------------------------------------------------------------
;; VS Code
;; ---------------------------------------------------------------------------

(defn expose-vscode
  "Exposes VS Code Server. Returns {:ok preview-url-or-nil}."
  [id opts]
  (let [r (client/post "/xrpc/io.pocketenv.sandbox.exposeVscode"
                       nil
                       (assoc opts :params {"id" id}))]
    (if-let [data (:ok r)]
      {:ok (get data "previewUrl")}
      r)))

;; ---------------------------------------------------------------------------
;; Actor / Profile
;; ---------------------------------------------------------------------------

(defn me
  "Fetches the profile of the currently authenticated user."
  [opts]
  (let [r (client/get "/xrpc/io.pocketenv.actor.getProfile" opts)]
    (if-let [data (:ok r)]
      {:ok (types/profile-from-map data)}
      r)))

(defn get-profile
  "Fetches the profile of any actor by DID or handle."
  [did opts]
  (let [r (client/get "/xrpc/io.pocketenv.actor.getProfile"
                      (assoc opts :params {"did" did}))]
    (if-let [data (:ok r)]
      {:ok (types/profile-from-map data)}
      r)))

;; ---------------------------------------------------------------------------
;; Secrets
;; ---------------------------------------------------------------------------

(defn list-secrets
  "Lists all secrets for a sandbox. Options: :limit (100), :offset (0), :token"
  [sandbox-id opts]
  (let [r (client/get "/xrpc/io.pocketenv.secret.getSecrets"
                      (assoc opts :params {"sandboxId" sandbox-id
                                           "limit"     (get opts :limit 100)
                                           "offset"    (get opts :offset 0)}))]
    (if-let [data (:ok r)]
      {:ok (mapv types/secret-from-map (get data "secrets"))}
      r)))

(defn add-secret
  "Adds an encrypted secret to a sandbox."
  [sandbox-id name value opts]
  (let [encrypted (crypto/encrypt value)]
    (client/post "/xrpc/io.pocketenv.secret.addSecret"
                 {"secret" {"sandboxId" sandbox-id "name" name "value" encrypted}}
                 opts)))

(defn delete-secret
  "Deletes a secret by its id."
  [id opts]
  (client/post "/xrpc/io.pocketenv.secret.deleteSecret"
               nil
               (assoc opts :params {"id" id})))

;; ---------------------------------------------------------------------------
;; SSH Keys
;; ---------------------------------------------------------------------------

(defn get-ssh-keys
  "Fetches the SSH key pair for a sandbox."
  [sandbox-id opts]
  (let [r (client/get "/xrpc/io.pocketenv.sandbox.getSshKeys"
                      (assoc opts :params {"id" sandbox-id}))]
    (if-let [data (:ok r)]
      {:ok (types/ssh-key-from-map data)}
      r)))

(defn put-ssh-keys
  "Stores an SSH key pair. Private key is encrypted client-side."
  [sandbox-id private-key public-key opts]
  (let [encrypted-private-key (crypto/encrypt private-key)
        redacted              (redact-ssh-private-key private-key)]
    (client/post "/xrpc/io.pocketenv.sandbox.putSshKeys"
                 {"id"         sandbox-id
                  "privateKey" encrypted-private-key
                  "publicKey"  public-key
                  "redacted"   redacted}
                 opts)))

;; ---------------------------------------------------------------------------
;; Tailscale
;; ---------------------------------------------------------------------------

(defn get-tailscale-auth-key
  "Fetches the Tailscale auth key for a sandbox."
  [sandbox-id opts]
  (let [r (client/get "/xrpc/io.pocketenv.sandbox.getTailscaleAuthKey"
                      (assoc opts :params {"id" sandbox-id}))]
    (if-let [data (:ok r)]
      {:ok (types/tailscale-auth-key-from-map data)}
      r)))

(defn put-tailscale-auth-key
  "Stores a Tailscale auth key. Must start with \"tskey-auth-\". Encrypted client-side."
  [sandbox-id auth-key opts]
  (when-not (str/starts-with? auth-key "tskey-auth-")
    (throw (ex-info "Tailscale auth key must start with \"tskey-auth-\"" {:auth-key auth-key})))
  (let [encrypted (crypto/encrypt auth-key)
        redacted  (redact-tailscale-key auth-key)]
    (client/post "/xrpc/io.pocketenv.sandbox.putTailscaleAuthKey"
                 {"id" sandbox-id "authKey" encrypted "redacted" redacted}
                 opts)))
