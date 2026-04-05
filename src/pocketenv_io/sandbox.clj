(ns pocketenv-io.sandbox
  "Sandbox record and pipe-safe operations.

  The Sandbox record is the central type of the SDK. Obtain one from
  pocketenv-io.pocketenv/create-sandbox or get-sandbox, then pipe
  operations with Clojure's -> threading macro:

    (-> (pocketenv/create-sandbox \"my-box\")
        (sandbox/start)
        (sandbox/wait-until-running)
        (sandbox/exec \"clojure\" [\"-M:test\"]))

  Every operation accepts either a bare Sandbox record or an {:ok sandbox}
  result map, enabling seamless threading. Passing {:error reason} raises
  an exception, keeping error handling explicit.")

;; ---------------------------------------------------------------------------
;; Data types
;; ---------------------------------------------------------------------------

(defrecord Sandbox
  [id name provider base-sandbox display-name uri description
   topics logo readme repo vcpus memory disk installs status
   started-at created-at owner])

(defrecord Profile
  [id did handle display-name avatar created-at updated-at])

(defrecord Port
  [port description preview-url])

(defrecord ExecResult
  [stdout stderr exit-code])

(defrecord Secret
  [id name created-at])

(defrecord SshKey
  [id private-key public-key created-at])

(defrecord TailscaleAuthKey
  [id auth-key created-at])

;; ---------------------------------------------------------------------------
;; Constructors from raw API maps
;; ---------------------------------------------------------------------------

(defn profile-from-map [m]
  (when m
    (map->Profile
      {:id           (get m "id")
       :did          (get m "did")
       :handle       (get m "handle")
       :display-name (get m "displayName")
       :avatar       (get m "avatar")
       :created-at   (get m "createdAt")
       :updated-at   (get m "updatedAt")})))

(defn- parse-status [s]
  (case s
    "RUNNING" :running
    "STOPPED" :stopped
    :unknown))

(defn sandbox-from-map [m]
  (map->Sandbox
    {:id           (get m "id")
     :name         (get m "name")
     :provider     (get m "provider")
     :base-sandbox (get m "baseSandbox")
     :display-name (get m "displayName")
     :uri          (get m "uri")
     :description  (get m "description")
     :topics       (get m "topics")
     :logo         (get m "logo")
     :readme       (get m "readme")
     :repo         (get m "repo")
     :vcpus        (get m "vcpus")
     :memory       (get m "memory")
     :disk         (get m "disk")
     :installs     (or (get m "installs") 0)
     :status       (parse-status (get m "status"))
     :started-at   (get m "startedAt")
     :created-at   (get m "createdAt")
     :owner        (profile-from-map (get m "owner"))}))

(defn port-from-map [m]
  (map->Port
    {:port        (get m "port")
     :description (get m "description")
     :preview-url (get m "previewUrl")}))

(defn exec-result-from-map [m]
  (map->ExecResult
    {:stdout    (or (get m "stdout") "")
     :stderr    (or (get m "stderr") "")
     :exit-code (or (get m "exitCode") 0)}))

(defn secret-from-map [m]
  (map->Secret
    {:id         (get m "id")
     :name       (get m "name")
     :created-at (get m "createdAt")}))

(defn ssh-key-from-map [m]
  (map->SshKey
    {:id          (get m "id")
     :private-key (get m "privateKey")
     :public-key  (get m "publicKey")
     :created-at  (get m "createdAt")}))

(defn tailscale-auth-key-from-map [m]
  (map->TailscaleAuthKey
    {:id         (get m "id")
     :auth-key   (get m "authKey")
     :created-at (get m "createdAt")}))

;; ---------------------------------------------------------------------------
;; Pipe-safety helper
;; ---------------------------------------------------------------------------

(defn- unwrap
  "Accepts a bare Sandbox or {:ok sandbox}. Throws on {:error ...}."
  [x]
  (cond
    (instance? Sandbox x)                              x
    (and (map? x) (instance? Sandbox (:ok x)))         (:ok x)
    :else (throw (ex-info "Expected a Sandbox or {:ok Sandbox}" {:value x}))))

;; ---------------------------------------------------------------------------
;; Lifecycle — forward-declared; resolved against api ns at runtime
;; ---------------------------------------------------------------------------

(defn start
  "Starts the sandbox. Re-fetches state after the call.

  Options: :repo, :keep-alive, :token"
  ([sandbox-or-result] (start sandbox-or-result {}))
  ([sandbox-or-result opts]
   (let [sb  (unwrap sandbox-or-result)
         api (requiring-resolve 'pocketenv-io.api/start-sandbox)
         get (requiring-resolve 'pocketenv-io.api/get-sandbox)
         r   (api (:name sb) opts)]
     (if (:error r)
       r
       (get (:name sb) opts)))))

(defn stop
  "Stops the sandbox. Re-fetches state after the call.

  Options: :token"
  ([sandbox-or-result] (stop sandbox-or-result {}))
  ([sandbox-or-result opts]
   (let [sb  (unwrap sandbox-or-result)
         api (requiring-resolve 'pocketenv-io.api/stop-sandbox)
         get (requiring-resolve 'pocketenv-io.api/get-sandbox)
         r   (api (:name sb) opts)]
     (if (:error r)
       r
       (get (:name sb) opts)))))

(defn delete
  "Deletes the sandbox permanently. Returns {:ok sandbox} with last known state.

  Options: :token"
  ([sandbox-or-result] (delete sandbox-or-result {}))
  ([sandbox-or-result opts]
   (let [sb  (unwrap sandbox-or-result)
         api (requiring-resolve 'pocketenv-io.api/delete-sandbox)
         r   (api (:name sb) opts)]
     (if (:error r)
       r
       {:ok sb}))))

(defn wait-until-running
  "Polls until the sandbox status becomes :running.

  Options: :timeout-ms (default 60000), :interval-ms (default 2000), :token"
  ([sandbox-or-result] (wait-until-running sandbox-or-result {}))
  ([sandbox-or-result opts]
   (let [sb  (unwrap sandbox-or-result)
         api (requiring-resolve 'pocketenv-io.api/wait-until-running)]
     (api (:name sb) opts))))

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(defn exec
  "Executes a shell command inside the sandbox.

  Returns {:ok ExecResult} with :stdout, :stderr, :exit-code.

  Options: :token"
  ([sandbox-or-result cmd] (exec sandbox-or-result cmd [] {}))
  ([sandbox-or-result cmd args] (exec sandbox-or-result cmd args {}))
  ([sandbox-or-result cmd args opts]
   (let [sb  (unwrap sandbox-or-result)
         api (requiring-resolve 'pocketenv-io.api/exec)]
     (api (:name sb) cmd args opts))))

;; ---------------------------------------------------------------------------
;; Ports
;; ---------------------------------------------------------------------------

(defn expose
  "Exposes a port on the sandbox. Returns {:ok preview-url-or-nil}.

  Options: :description, :token"
  ([sandbox-or-result port] (expose sandbox-or-result port {}))
  ([sandbox-or-result port opts]
   (let [sb  (unwrap sandbox-or-result)
         api (requiring-resolve 'pocketenv-io.api/expose-port)]
     (api (:name sb) port opts))))

(defn unexpose
  "Removes an exposed port. Returns {:ok sandbox}.

  Options: :token"
  ([sandbox-or-result port] (unexpose sandbox-or-result port {}))
  ([sandbox-or-result port opts]
   (let [sb  (unwrap sandbox-or-result)
         api (requiring-resolve 'pocketenv-io.api/unexpose-port)
         r   (api (:name sb) port opts)]
     (if (:error r)
       r
       {:ok sb}))))

(defn list-ports
  "Lists all exposed ports on the sandbox.

  Returns {:ok [Port]}"
  ([sandbox-or-result] (list-ports sandbox-or-result {}))
  ([sandbox-or-result opts]
   (let [sb  (unwrap sandbox-or-result)
         api (requiring-resolve 'pocketenv-io.api/list-ports)]
     (api (:name sb) opts))))

;; ---------------------------------------------------------------------------
;; VS Code
;; ---------------------------------------------------------------------------

(defn vscode
  "Exposes VS Code Server and returns its URL.

  Returns {:ok preview-url-or-nil}

  Options: :token"
  ([sandbox-or-result] (vscode sandbox-or-result {}))
  ([sandbox-or-result opts]
   (let [sb  (unwrap sandbox-or-result)
         api (requiring-resolve 'pocketenv-io.api/expose-vscode)]
     (api (:name sb) opts))))

;; ---------------------------------------------------------------------------
;; Secrets
;; ---------------------------------------------------------------------------

(defn list-secrets
  "Lists all secrets for the sandbox.

  Options: :limit (default 100), :offset (default 0), :token"
  ([sandbox-or-result] (list-secrets sandbox-or-result {}))
  ([sandbox-or-result opts]
   (let [sb  (unwrap sandbox-or-result)
         api (requiring-resolve 'pocketenv-io.api/list-secrets)]
     (api (:id sb) opts))))

(defn set-secret
  "Adds an encrypted secret to the sandbox.

  Options: :token"
  ([sandbox-or-result name value] (set-secret sandbox-or-result name value {}))
  ([sandbox-or-result name value opts]
   (let [sb  (unwrap sandbox-or-result)
         api (requiring-resolve 'pocketenv-io.api/add-secret)]
     (api (:id sb) name value opts))))

(defn delete-secret
  "Deletes a secret by its id.

  Options: :token"
  ([sandbox-or-result id] (delete-secret sandbox-or-result id {}))
  ([sandbox-or-result id opts]
   (unwrap sandbox-or-result)
   (let [api (requiring-resolve 'pocketenv-io.api/delete-secret)]
     (api id opts))))

;; ---------------------------------------------------------------------------
;; SSH Keys
;; ---------------------------------------------------------------------------

(defn get-ssh-keys
  "Fetches the SSH key pair for the sandbox.

  Options: :token"
  ([sandbox-or-result] (get-ssh-keys sandbox-or-result {}))
  ([sandbox-or-result opts]
   (let [sb  (unwrap sandbox-or-result)
         api (requiring-resolve 'pocketenv-io.api/get-ssh-keys)]
     (api (:id sb) opts))))

(defn set-ssh-keys
  "Stores an SSH key pair. The private key is encrypted client-side.

  Options: :token"
  ([sandbox-or-result private-key public-key] (set-ssh-keys sandbox-or-result private-key public-key {}))
  ([sandbox-or-result private-key public-key opts]
   (let [sb  (unwrap sandbox-or-result)
         api (requiring-resolve 'pocketenv-io.api/put-ssh-keys)]
     (api (:id sb) private-key public-key opts))))

;; ---------------------------------------------------------------------------
;; Tailscale
;; ---------------------------------------------------------------------------

(defn get-tailscale-auth-key
  "Fetches the Tailscale auth key for the sandbox.

  Options: :token"
  ([sandbox-or-result] (get-tailscale-auth-key sandbox-or-result {}))
  ([sandbox-or-result opts]
   (let [sb  (unwrap sandbox-or-result)
         api (requiring-resolve 'pocketenv-io.api/get-tailscale-auth-key)]
     (api (:id sb) opts))))

(defn set-tailscale-auth-key
  "Stores a Tailscale auth key. Must start with \"tskey-auth-\".
   Encrypted client-side before transmission.

  Options: :token"
  ([sandbox-or-result auth-key] (set-tailscale-auth-key sandbox-or-result auth-key {}))
  ([sandbox-or-result auth-key opts]
   (let [sb  (unwrap sandbox-or-result)
         api (requiring-resolve 'pocketenv-io.api/put-tailscale-auth-key)]
     (api (:id sb) auth-key opts))))

;; ---------------------------------------------------------------------------
;; Copy
;; ---------------------------------------------------------------------------

(defn upload
  "Uploads a local file or directory to `sandbox-path` inside the sandbox.

  The local path is compressed into a tar.gz archive, uploaded to storage,
  and then extracted by the sandbox at `sandbox-path`.

  Options: :token"
  ([sandbox-or-result local-path sandbox-path] (upload sandbox-or-result local-path sandbox-path {}))
  ([sandbox-or-result local-path sandbox-path opts]
   (let [sb   (unwrap sandbox-or-result)
         copy (requiring-resolve 'pocketenv-io.copy/upload)]
     (copy (:id sb) local-path sandbox-path opts))))

(defn download
  "Downloads `sandbox-path` from the sandbox and extracts it to `local-path`.

  The sandbox compresses the path into a tar.gz archive which is then
  downloaded and extracted locally.

  Options: :token"
  ([sandbox-or-result sandbox-path local-path] (download sandbox-or-result sandbox-path local-path {}))
  ([sandbox-or-result sandbox-path local-path opts]
   (let [sb   (unwrap sandbox-or-result)
         copy (requiring-resolve 'pocketenv-io.copy/download)]
     (copy (:id sb) sandbox-path local-path opts))))

(defn copy-to
  "Copies `src-path` from this sandbox into `dest-path` inside `dest-sandbox-id`.
  No local I/O is involved — the transfer goes directly through storage.

  Options: :token"
  ([sandbox-or-result dest-sandbox-id src-path dest-path]
   (copy-to sandbox-or-result dest-sandbox-id src-path dest-path {}))
  ([sandbox-or-result dest-sandbox-id src-path dest-path opts]
   (let [sb   (unwrap sandbox-or-result)
         copy (requiring-resolve 'pocketenv-io.copy/to)]
     (copy (:id sb) dest-sandbox-id src-path dest-path opts))))
