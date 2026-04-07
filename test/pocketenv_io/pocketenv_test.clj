(ns pocketenv-io.pocketenv-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [pocketenv-io.copy :as copy]
            [pocketenv-io.crypto :as crypto]
            [pocketenv-io.sandbox :as sandbox]
            [pocketenv-io.api :as api]))

(deftest redact-tailscale-key-test
  (testing "masks middle characters, preserving first 11 and last 3"
    (let [key "tskey-auth-ABCDEFGHIJKLMNOP"
          ;; > 14 chars so masking applies
          ;; first 11: "tskey-auth-", last 3: "NOP", middle masked
          result (#'api/redact-tailscale-key key)]
      (is (clojure.string/starts-with? result "tskey-auth-"))
      (is (clojure.string/ends-with? result "NOP"))
      (is (clojure.string/includes? result "*"))))

  (testing "short keys are returned unchanged"
    (is (= "short" (#'api/redact-tailscale-key "short")))))

(deftest sandbox-from-map-test
  (testing "parses sandbox map correctly"
    (let [m   {"id"          "abc"
               "name"        "my-box"
               "provider"    "cloudflare"
               "baseSandbox" "openclaw"
               "displayName" "My Box"
               "status"      "RUNNING"
               "installs"    5}
          sb  (sandbox/sandbox-from-map m)]
      (is (instance? pocketenv_io.sandbox.Sandbox sb))
      (is (= "abc" (:id sb)))
      (is (= "my-box" (:name sb)))
      (is (= :running (:status sb)))
      (is (= 5 (:installs sb)))
      (is (= "openclaw" (:base-sandbox sb)))))

  (testing "defaults installs to 0"
    (let [sb (sandbox/sandbox-from-map {"id" "x"})]
      (is (= 0 (:installs sb)))))

  (testing "unknown status"
    (let [sb (sandbox/sandbox-from-map {"status" "PENDING"})]
      (is (= :unknown (:status sb))))))

(deftest profile-from-map-test
  (testing "parses profile map"
    (let [p (sandbox/profile-from-map {"id"          "1"
                                       "did"         "did:plc:abc"
                                       "handle"      "alice.bsky.social"
                                       "displayName" "Alice"
                                       "avatar"      nil
                                       "createdAt"   "2024-01-01"})]
      (is (= "did:plc:abc" (:did p)))
      (is (= "alice.bsky.social" (:handle p)))
      (is (= "Alice" (:display-name p)))))

  (testing "returns nil for nil input"
    (is (nil? (sandbox/profile-from-map nil)))))

(deftest exec-result-from-map-test
  (testing "parses exec result"
    (let [r (sandbox/exec-result-from-map {"stdout" "hello\n" "stderr" "" "exitCode" 0})]
      (is (= "hello\n" (:stdout r)))
      (is (= 0 (:exit-code r)))))

  (testing "defaults empty fields"
    (let [r (sandbox/exec-result-from-map {})]
      (is (= "" (:stdout r)))
      (is (= "" (:stderr r)))
      (is (= 0 (:exit-code r))))))

(deftest sandbox-unwrap-test
  (testing "sandbox/start rejects error result"
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'sandbox/unwrap {:error :not_logged_in}))))

  (testing "unwrap passes through bare Sandbox"
    (let [sb (sandbox/map->Sandbox {:id "x" :name "x"})]
      (is (= sb (#'sandbox/unwrap sb)))))

  (testing "unwrap extracts from {:ok sandbox}"
    (let [sb (sandbox/map->Sandbox {:id "x" :name "x"})]
      (is (= sb (#'sandbox/unwrap {:ok sb}))))))

;; ---------------------------------------------------------------------------
;; copy/compress + copy/decompress
;; ---------------------------------------------------------------------------

(deftest compress-decompress-file-test
  (testing "single file roundtrip"
    (let [tmp-src  (java.io.File/createTempFile "pe-test-src" ".txt")
          tmp-dest (java.io.File/createTempFile "pe-test-dest" "")
          dest-dir (io/file (System/getProperty "java.io.tmpdir")
                            (str "pe-test-dest-dir-" (System/currentTimeMillis)))]
      (try
        (.delete tmp-dest)
        (spit tmp-src "hello from pocketenv")
        (let [compress-r (copy/compress (.getAbsolutePath tmp-src))]
          (is (nil? (:error compress-r)))
          (let [archive (:ok compress-r)
                _       (is (some? archive))
                dc-r    (copy/decompress archive (.getAbsolutePath dest-dir))]
            (is (= :ok dc-r))
            (let [extracted (io/file dest-dir (.getName tmp-src))]
              (is (.exists extracted))
              (is (= "hello from pocketenv" (slurp extracted))))
            (.delete archive)))
        (finally
          (.delete tmp-src)
          (run! #(.delete %) (file-seq dest-dir)))))))

(deftest compress-decompress-dir-test
  (testing "directory roundtrip"
    (let [src-dir  (io/file (System/getProperty "java.io.tmpdir")
                            (str "pe-test-src-dir-" (System/currentTimeMillis)))
          dest-dir (io/file (System/getProperty "java.io.tmpdir")
                            (str "pe-test-dest-dir-" (System/currentTimeMillis)))]
      (try
        (.mkdirs src-dir)
        (spit (io/file src-dir "a.txt") "file-a")
        (spit (io/file src-dir "b.txt") "file-b")
        (let [compress-r (copy/compress (.getAbsolutePath src-dir))]
          (is (nil? (:error compress-r)))
          (let [archive (:ok compress-r)
                dc-r    (copy/decompress archive (.getAbsolutePath dest-dir))]
            (is (= :ok dc-r))
            (is (= "file-a" (slurp (io/file dest-dir "a.txt"))))
            (is (= "file-b" (slurp (io/file dest-dir "b.txt"))))
            (.delete archive)))
        (finally
          (run! #(.delete %) (file-seq src-dir))
          (run! #(.delete %) (file-seq dest-dir)))))))

;; ---------------------------------------------------------------------------
;; copy/storage-url
;; ---------------------------------------------------------------------------

(deftest storage-url-test
  (testing "returns default when POCKETENV_STORAGE_URL is not set"
    (when (nil? (System/getenv "POCKETENV_STORAGE_URL"))
      (is (= "https://sandbox.pocketenv.io" (copy/storage-url))))))

;; ---------------------------------------------------------------------------
;; sandbox copy delegation
;; ---------------------------------------------------------------------------

(deftest sandbox-upload-test
  (testing "upload delegates to copy/upload with sandbox id"
    (let [calls (atom nil)
          sb    (sandbox/map->Sandbox {:id "sb-123" :name "my-box"})]
      (with-redefs [pocketenv-io.copy/upload
                    (fn [sandbox-id local-path sandbox-path opts]
                      (reset! calls {:sandbox-id   sandbox-id
                                     :local-path   local-path
                                     :sandbox-path sandbox-path
                                     :opts         opts})
                      :ok)]
        (is (= :ok (sandbox/upload sb "./src" "/workspace" {:token "t"})))
        (is (= {:sandbox-id   "sb-123"
                :local-path   "./src"
                :sandbox-path "/workspace"
                :opts         {:token "t"}}
               @calls))))))

(deftest sandbox-download-test
  (testing "download delegates to copy/download with sandbox id"
    (let [calls (atom nil)
          sb    (sandbox/map->Sandbox {:id "sb-456" :name "my-box"})]
      (with-redefs [pocketenv-io.copy/download
                    (fn [sandbox-id sandbox-path local-path opts]
                      (reset! calls {:sandbox-id   sandbox-id
                                     :sandbox-path sandbox-path
                                     :local-path   local-path
                                     :opts         opts})
                      :ok)]
        (is (= :ok (sandbox/download sb "/workspace" "./output" {:token "t"})))
        (is (= {:sandbox-id   "sb-456"
                :sandbox-path "/workspace"
                :local-path   "./output"
                :opts         {:token "t"}}
               @calls))))))

(deftest sandbox-copy-to-test
  (testing "copy-to delegates to copy/to with sandbox id"
    (let [calls (atom nil)
          sb    (sandbox/map->Sandbox {:id "sb-789" :name "my-box"})]
      (with-redefs [pocketenv-io.copy/to
                    (fn [src-id dest-id src-path dest-path opts]
                      (reset! calls {:src-id    src-id
                                     :dest-id   dest-id
                                     :src-path  src-path
                                     :dest-path dest-path
                                     :opts      opts})
                      :ok)]
        (is (= :ok (sandbox/copy-to sb "dest-999" "/src" "/dest" {:token "t"})))
        (is (= {:src-id    "sb-789"
                :dest-id   "dest-999"
                :src-path  "/src"
                :dest-path "/dest"
                :opts      {:token "t"}}
               @calls))))))

;; ---------------------------------------------------------------------------
;; Backup
;; ---------------------------------------------------------------------------

(deftest backup-from-map-test
  (testing "parses backup map"
    (let [b (sandbox/backup-from-map {"id"          "bk-1"
                                      "directory"   "/workspace"
                                      "description" "my backup"
                                      "expiresAt"   "2025-01-01"
                                      "createdAt"   "2024-01-01"})]
      (is (instance? pocketenv_io.sandbox.Backup b))
      (is (= "bk-1" (:id b)))
      (is (= "/workspace" (:directory b)))
      (is (= "my backup" (:description b)))
      (is (= "2025-01-01" (:expires-at b)))
      (is (= "2024-01-01" (:created-at b)))))

  (testing "optional fields default to nil"
    (let [b (sandbox/backup-from-map {"id" "bk-2" "directory" "/home" "createdAt" "2024-01-01"})]
      (is (nil? (:description b)))
      (is (nil? (:expires-at b))))))

(deftest sandbox-create-backup-test
  (testing "create-backup delegates to api/create-backup with sandbox id"
    (let [calls (atom nil)
          sb    (sandbox/map->Sandbox {:id "sb-123" :name "my-box"})]
      (with-redefs [pocketenv-io.api/create-backup
                    (fn [sandbox-id directory opts]
                      (reset! calls {:sandbox-id sandbox-id
                                     :directory  directory
                                     :opts       opts})
                      {:ok nil})]
        (is (= {:ok nil} (sandbox/create-backup sb "/workspace" {:token "t" :description "snap"})))
        (is (= {:sandbox-id "sb-123"
                :directory  "/workspace"
                :opts       {:token "t" :description "snap"}}
               @calls))))))

(deftest sandbox-list-backups-test
  (testing "list-backups delegates to api/list-backups with sandbox id"
    (let [calls (atom nil)
          sb    (sandbox/map->Sandbox {:id "sb-456" :name "my-box"})
          fake  [(sandbox/map->Backup {:id "bk-1" :directory "/workspace"})]]
      (with-redefs [pocketenv-io.api/list-backups
                    (fn [sandbox-id opts]
                      (reset! calls {:sandbox-id sandbox-id :opts opts})
                      {:ok fake})]
        (is (= {:ok fake} (sandbox/list-backups sb {:token "t"})))
        (is (= {:sandbox-id "sb-456" :opts {:token "t"}} @calls))))))

(deftest sandbox-restore-backup-test
  (testing "restore-backup delegates to api/restore-backup with backup-id"
    (let [calls (atom nil)]
      (with-redefs [pocketenv-io.api/restore-backup
                    (fn [backup-id opts]
                      (reset! calls {:backup-id backup-id :opts opts})
                      {:ok nil})]
        (is (= {:ok nil} (sandbox/restore-backup "bk-99" {:token "t"})))
        (is (= {:backup-id "bk-99" :opts {:token "t"}} @calls))))))
