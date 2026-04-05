(ns pocketenv-io.copy
  "Handles file and directory transfers between local paths and sandboxes,
   and between sandboxes. Not part of the public API — use sandbox/upload,
   sandbox/download, and sandbox/copy-to instead."
  (:require [pocketenv-io.client :as client]
            [clj-http.client :as http]
            [clojure.java.io :as io])
  (:import [java.io File FileInputStream FileOutputStream BufferedOutputStream]
           [java.security MessageDigest]
           [org.apache.commons.compress.archivers.tar
            TarArchiveEntry TarArchiveInputStream TarArchiveOutputStream]
           [org.apache.commons.compress.compressors.gzip
            GzipCompressorInputStream GzipCompressorOutputStream]))

(def ^:private default-storage-url "https://sandbox.pocketenv.io")

(defn storage-url []
  (or (System/getenv "POCKETENV_STORAGE_URL") default-storage-url))

;; ---------------------------------------------------------------------------
;; Compression helpers
;; ---------------------------------------------------------------------------

(defn- walk-dir
  "Returns all regular file paths under `base-file` relative to it."
  [^File base prefix]
  (let [dir (if (empty? prefix) base (io/file base prefix))]
    (->> (.listFiles dir)
         (mapcat (fn [^File f]
                   (let [rel (if (empty? prefix) (.getName f) (str prefix "/" (.getName f)))]
                     (cond
                       (.isFile f)      [rel]
                       (.isDirectory f) (walk-dir base rel)
                       :else            [])))))))

(defn- sha256-hex [s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes ^String s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn compress
  "Creates a .tar.gz archive from `local-path` (file or directory).
  Returns {:ok java.io.File} or {:error reason}."
  [local-path]
  (try
    (let [src    (io/file local-path)
          archive (File/createTempFile (sha256-hex local-path) ".tar.gz")]
      (with-open [taros (-> archive
                            FileOutputStream.
                            BufferedOutputStream.
                            GzipCompressorOutputStream.
                            TarArchiveOutputStream.)]
        (.setLongFileMode taros TarArchiveOutputStream/LONGFILE_GNU)
        (if (.isFile src)
          (let [entry (TarArchiveEntry. (.getName src))]
            (.setSize entry (.length src))
            (.putArchiveEntry taros entry)
            (io/copy src taros)
            (.closeArchiveEntry taros))
          (doseq [rel (walk-dir src "")]
            (let [f     (io/file src rel)
                  entry (TarArchiveEntry. rel)]
              (.setSize entry (.length f))
              (.putArchiveEntry taros entry)
              (io/copy f taros)
              (.closeArchiveEntry taros))))
        (.finish taros))
      {:ok archive})
    (catch Exception e
      {:error (.getMessage e)})))

(defn decompress
  "Extracts a .tar.gz `archive` file into `dest-path`.
  Returns :ok or {:error reason}."
  [^File archive dest-path]
  (try
    (let [dest (io/file dest-path)]
      (.mkdirs dest)
      (with-open [tais (-> archive
                           FileInputStream.
                           GzipCompressorInputStream.
                           TarArchiveInputStream.)]
        (loop [entry (.getNextTarEntry tais)]
          (when entry
            (let [out-file (io/file dest (.getName entry))]
              (io/make-parents out-file)
              (when-not (.isDirectory entry)
                (with-open [fos (FileOutputStream. out-file)]
                  (io/copy tais fos))))
            (recur (.getNextTarEntry tais)))))
      :ok)
    (catch Exception e
      {:error (.getMessage e)})))

(defn- temp-file []
  (File/createTempFile "pocketenv" ".tar.gz"))

;; ---------------------------------------------------------------------------
;; Storage HTTP helpers
;; ---------------------------------------------------------------------------

(defn- resolve-token [opts]
  (if-let [t (:token opts)]
    {:ok t}
    (client/token)))

(defn- upload-to-storage
  "POSTs `archive` as multipart/form-data to storage. Returns {:ok uuid} or {:error ...}."
  [^File archive opts]
  (let [token-result (resolve-token opts)]
    (if (:error token-result)
      token-result
      (try
        (let [t    (:ok token-result)
              url  (str (storage-url) "/cp")
              resp (http/post url
                              {:multipart        [{:name      "file"
                                                   :content   archive
                                                   :filename  "archive.tar.gz"
                                                   :mime-type "application/gzip"}]
                               :headers          {"Authorization" (str "Bearer " t)}
                               :as               :json-string-keys
                               :throw-exceptions false})]
          (if (<= 200 (:status resp) 299)
            {:ok (get-in resp [:body "uuid"])}
            {:error {:status (:status resp) :body (:body resp)}}))
        (catch Exception e
          {:error (.getMessage e)})))))

(defn- download-from-storage
  "GETs the archive for `uuid` from storage and writes it to `dest-file`. Returns :ok or {:error ...}."
  [uuid ^File dest-file opts]
  (let [token-result (resolve-token opts)]
    (if (:error token-result)
      token-result
      (try
        (let [t    (:ok token-result)
              url  (str (storage-url) "/cp/" uuid)
              resp (http/get url
                             {:headers          {"Authorization" (str "Bearer " t)}
                              :as               :byte-array
                              :throw-exceptions false})]
          (if (<= 200 (:status resp) 299)
            (do (io/copy (:body resp) dest-file) :ok)
            {:error {:status (:status resp) :body (:body resp)}}))
        (catch Exception e
          {:error (.getMessage e)})))))

;; ---------------------------------------------------------------------------
;; Public operations
;; ---------------------------------------------------------------------------

(defn upload
  "Compress `local-path` and upload it to `sandbox-path` inside the sandbox."
  [sandbox-id local-path sandbox-path opts]
  (let [compress-r (compress local-path)]
    (if (:error compress-r)
      compress-r
      (let [archive  (:ok compress-r)
            upload-r (upload-to-storage archive opts)
            result   (if (:error upload-r)
                       upload-r
                       (let [api    (requiring-resolve 'pocketenv-io.api/pull-directory)
                             pull-r (api sandbox-id (:ok upload-r) sandbox-path opts)]
                         (if (:error pull-r)
                           pull-r
                           :ok)))]
        (.delete archive)
        result))))

(defn download
  "Push `sandbox-path` from the sandbox to storage, download it, and extract to `local-path`."
  [sandbox-id sandbox-path local-path opts]
  (let [archive (temp-file)
        push-api (requiring-resolve 'pocketenv-io.api/push-directory)
        push-r   (push-api sandbox-id sandbox-path opts)]
    (if (:error push-r)
      (do (.delete archive) push-r)
      (let [dl-r (download-from-storage (:ok push-r) archive opts)]
        (if (:error dl-r)
          (do (.delete archive) dl-r)
          (let [dc-r (decompress archive local-path)]
            (.delete archive)
            dc-r))))))

(defn to
  "Push `src-path` from `src-sandbox-id` to storage, then pull into `dest-path` inside `dest-sandbox-id`.
  No local I/O involved."
  [src-sandbox-id dest-sandbox-id src-path dest-path opts]
  (let [push-api (requiring-resolve 'pocketenv-io.api/push-directory)
        pull-api (requiring-resolve 'pocketenv-io.api/pull-directory)
        push-r   (push-api src-sandbox-id src-path opts)]
    (if (:error push-r)
      push-r
      (let [pull-r (pull-api dest-sandbox-id (:ok push-r) dest-path opts)]
        (if (:error pull-r)
          pull-r
          :ok)))))
