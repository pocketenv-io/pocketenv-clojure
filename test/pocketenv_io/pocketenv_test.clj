(ns pocketenv-io.pocketenv-test
  (:require [clojure.test :refer [deftest is testing]]
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
