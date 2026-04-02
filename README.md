# pocketenv-clojure

Clojure SDK for the [Pocketenv](https://pocketenv.io) sandbox platform.

## Installation

Add to your `deps.edn`:

```clojure
{:deps io.pocketenv/pocketenv {:mvn/version "0.1.0-SNAPSHOT"}}
```

## Configuration

Set one of the following before making any calls:

| Method          | Details                                        |
|-----------------|------------------------------------------------|
| Env var         | `POCKETENV_TOKEN=<your-token>`                 |
| Token file      | `~/.pocketenv/token.json` → `{"token": "..."}` |
| Per-call option | Pass `:token "..."` in the opts map            |

Optional env vars:

```
POCKETENV_API_URL    — API base URL (default: https://api.pocketenv.io)
POCKETENV_PUBLIC_KEY — server public key for client-side encryption (hex-encoded)
```

## Quick start

```clojure
(require '[pocketenv-io.pocketenv :as pocketenv]
         '[pocketenv-io.sandbox   :as sandbox])

;; Create, start, run a command, then clean up
(-> (pocketenv/create-sandbox "my-box")
    (sandbox/start)
    (sandbox/wait-until-running)
    (sandbox/exec "echo" ["hello from pocketenv"])
    ;; {:ok #ExecResult{:stdout "hello from pocketenv\n" :stderr "" :exit-code 0}}
    )
```

All functions return `{:ok value}` on success or `{:error reason}` on failure. The `sandbox/*` operations also accept an `{:ok sandbox}` result directly so they can be chained with `->` without unwrapping.

---

## Sandbox lifecycle

### Create

```clojure
;; Minimal — uses the default openclaw base image on Cloudflare
(pocketenv/create-sandbox "my-box")
;; => {:ok #Sandbox{:id "..." :name "my-box" :status :stopped ...}}

;; With options
(pocketenv/create-sandbox "my-box"
  {:provider   "daytona"
   :repo       "https://github.com/acme/my-app"
   :keep-alive true})
```

### Start / Stop / Delete

```clojure
(def sb (pocketenv/create-sandbox "my-box"))

(sandbox/start sb)
(sandbox/stop  sb)
(sandbox/delete sb)
```

### Wait until running

```clojure
(-> (pocketenv/create-sandbox "ci-runner")
    (sandbox/start)
    (sandbox/wait-until-running {:timeout-ms 120000 :interval-ms 3000}))
```

---

## Running commands

```clojure
(let [result (-> (pocketenv/get-sandbox "my-box")
                 (sandbox/exec "ls" ["-la" "/"]))]
  (println (get-in result [:ok :stdout])))

;; Run a Clojure test suite
(-> (pocketenv/get-sandbox "my-box")
    (sandbox/start)
    (sandbox/wait-until-running)
    (sandbox/exec "clojure" ["-M:test"]))
;; => {:ok #ExecResult{:stdout "..." :stderr "" :exit-code 0}}
```

`ExecResult` fields: `:stdout`, `:stderr`, `:exit-code`.

---

## Listing sandboxes

```clojure
;; Public sandbox catalog
(pocketenv/list-sandboxes)
;; => {:ok {:sandboxes [...] :total 42}}

;; With pagination
(pocketenv/list-sandboxes {:limit 10 :offset 20})

;; Sandboxes belonging to a specific actor (DID or handle)
(pocketenv/list-sandboxes-by-actor "alice.pocketenv.io")
(pocketenv/list-sandboxes-by-actor "did:plc:abc123")
```

---

## Exposing ports

```clojure
;; Expose port 3000 and get a public preview URL
(-> (pocketenv/get-sandbox "my-box")
    (sandbox/expose 3000 {:description "Web server"}))
;; => {:ok "https://preview.pocketenv.io/..."}

;; List all exposed ports
(-> (pocketenv/get-sandbox "my-box")
    (sandbox/list-ports))
;; => {:ok [#Port{:port 3000 :description "Web server" :preview-url "..."}]}

;; Remove a port
(-> (pocketenv/get-sandbox "my-box")
    (sandbox/unexpose 3000))
```

---

## VS Code Server

```clojure
(-> (pocketenv/get-sandbox "my-box")
    (sandbox/vscode))
;; => {:ok "https://preview.pocketenv.io/.../vscode/..."}
```

---

## Secrets

Secrets are encrypted client-side before transmission.

```clojure
;; Add a secret
(pocketenv/add-secret "sandbox-id" "DATABASE_URL" "postgres://...")

;; Or via the sandbox pipe
(-> (pocketenv/get-sandbox "my-box")
    (sandbox/set-secret "API_KEY" "sk-..."))

;; List secrets (names only — values are never returned)
(pocketenv/list-secrets "sandbox-id")
;; => {:ok [#Secret{:id "..." :name "DATABASE_URL" :created-at "..."}]}

;; Delete a secret by id
(pocketenv/delete-secret "secret-id")
```

---

## SSH keys

Private keys are encrypted client-side before transmission.

```clojure
;; Store a key pair
(pocketenv/put-ssh-keys "sandbox-id" private-key-str public-key-str)

;; Or via the sandbox pipe
(-> (pocketenv/get-sandbox "my-box")
    (sandbox/set-ssh-keys private-key-str public-key-str))

;; Retrieve
(pocketenv/get-ssh-keys "sandbox-id")
;; => {:ok #SshKey{:id "..." :public-key "..." :private-key "..." :created-at "..."}}
```

---

## Tailscale

```clojure
;; Store a Tailscale auth key (must start with "tskey-auth-")
(pocketenv/put-tailscale-auth-key "sandbox-id" "tskey-auth-...")

;; Or via the sandbox pipe
(-> (pocketenv/get-sandbox "my-box")
    (sandbox/set-tailscale-auth-key "tskey-auth-..."))

;; Retrieve
(pocketenv/get-tailscale-auth-key "sandbox-id")
;; => {:ok #TailscaleAuthKey{:id "..." :auth-key "..." :created-at "..."}}
```

---

## Actor / profile

```clojure
;; Your own profile (requires authentication)
(pocketenv/me)
;; => {:ok #Profile{:did "did:plc:..." :handle "alice" :display-name "Alice" ...}}

;; Any actor's profile
(pocketenv/get-profile "alice.pocketenv.io")
(pocketenv/get-profile "did:plc:abc123")
```

---

## Error handling

Every function returns `{:ok value}` or `{:error reason}`. Use `if-let` or pattern matching:

```clojure
(let [result (pocketenv/get-sandbox "does-not-exist")]
  (if-let [sb (:ok result)]
    (println "Found:" (:name sb))
    (println "Error:" (:error result))))
```

When chaining with `sandbox/*`, passing `{:error ...}` into any operation throws an `ExceptionInfo`, keeping failures explicit.

---

## License

MIT
