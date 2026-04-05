# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.3] - 2026-04-05

### Fixed
- `sandbox/upload` no longer returns `{:error "Input/uuid must be a string"}` — the storage response was parsed with keyword keys (`:uuid`) while the code looked up the string key `"uuid"`, always yielding `nil`. Changed `:as :json` to `:as :json-string-keys` in `upload-to-storage` so the UUID is correctly extracted and forwarded to `pullDirectory`.

## [0.1.2] - 2026-04-05

### Added
- File and directory copy interface: upload a local path to a sandbox, download a sandbox path to local disk, and copy directly between sandboxes with no local I/O (`sandbox/upload`, `sandbox/download`, `sandbox/copy-to`)
- Internal `pocketenv-io.copy` namespace handling tar.gz compression/decompression and storage HTTP transfers
- `api/push-directory` and `api/pull-directory` XRPC calls used by the copy module
- `POCKETENV_STORAGE_URL` env var to override the storage base URL (default: `https://sandbox.pocketenv.io`)
- `org.apache.commons/commons-compress` dependency for TAR archive support

## [0.1.1] - 2026-04-02

### Fixed
- JAR now includes `.clj` source files — `0.1.0` was missing them, causing `FileNotFoundException` on require
- `install` build task now writes pom, copies sources, and builds the JAR before installing to local Maven cache

## [0.1.0] - 2026-04-02

### Added
- Initial Pocketenv Clojure SDK
- Sandbox management: `create-sandbox`, `get-sandbox`, `list-sandboxes`, `list-sandboxes-by-actor`
- Sandbox lifecycle: `sandbox/start`, `stop`, `delete`, `wait-until-running`
- Command execution: `sandbox/exec`
- Port management: `sandbox/expose`, `unexpose`, `list-ports`
- VS Code Server exposure: `sandbox/vscode`
- Actor/profile: `me`, `get-profile`
- Secrets management: `list-secrets`, `add-secret`, `delete-secret` and pipe-friendly `sandbox/list-secrets`, `set-secret`, `delete-secret`
- SSH key management: `get-ssh-keys`, `put-ssh-keys` and `sandbox/get-ssh-keys`, `set-ssh-keys`
- Tailscale auth key management: `get-tailscale-auth-key`, `put-tailscale-auth-key` and `sandbox/get-tailscale-auth-key`, `set-tailscale-auth-key`
- `Sandbox`, `Profile`, `Port`, `ExecResult`, `Secret`, `SshKey`, `TailscaleAuthKey` records
- Client-side encryption via libsodium sealed boxes (`crypto_box_seal`) using `lazysodium-java`; private keys and auth keys are encrypted with the server's public key before transmission
- Redacted values stored alongside encrypted secrets (SSH private key body masked, Tailscale key middle masked)
- Default server public key bundled so no configuration is required for the production API
- Pipe-safe sandbox operations: every `sandbox/*` function accepts a bare `Sandbox` record or an `{:ok sandbox}` result, enabling `->` threading without manual unwrapping
- Token resolved from `POCKETENV_TOKEN` env var or `~/.pocketenv/token.json`
- MIT License

[0.1.3]: https://github.com/pocketenv-io/pocketenv-clojure/releases/tag/v0.1.3
[0.1.2]: https://github.com/pocketenv-io/pocketenv-clojure/releases/tag/v0.1.2
[0.1.1]: https://github.com/pocketenv-io/pocketenv-clojure/releases/tag/v0.1.1
[0.1.0]: https://github.com/pocketenv-io/pocketenv-clojure/releases/tag/v0.1.0
