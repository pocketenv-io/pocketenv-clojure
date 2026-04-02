(ns pocketenv-io.client
  "Low-level HTTP client for the Pocketenv XRPC API.

  Configuration (resolved in priority order):
    - POCKETENV_API_URL env var   (or default https://api.pocketenv.io)
    - POCKETENV_TOKEN env var
    - ~/.pocketenv/token.json file ({\"token\": \"...\"})"
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(def ^:private default-api-url "https://api.pocketenv.io")

(defn base-url []
  (or (System/getenv "POCKETENV_API_URL") default-api-url))

(defn- read-token-file []
  (let [path (str (System/getProperty "user.home") "/.pocketenv/token.json")]
    (try
      (let [data (json/parse-string (slurp path))]
        (get data "token"))
      (catch Exception _ nil))))

(defn token []
  (if-let [t (or (System/getenv "POCKETENV_TOKEN") (read-token-file))]
    {:ok t}
    {:error :not_logged_in}))

(defn- resolve-token [opts]
  (if-let [t (:token opts)]
    {:ok t}
    (token)))

(defn- parse-body [body]
  (if (and body (seq body))
    (try (json/parse-string body) (catch Exception _ body))
    nil))

(defn- request [method path body opts]
  (let [token-result (resolve-token opts)]
    (if (:error token-result)
      token-result
      (let [t      (:ok token-result)
            url    (str (base-url) path)
            params (:params opts {})
            req    (cond-> {:method              method
                            :url                 url
                            :headers             {"Authorization"  (str "Bearer " t)
                                                  "Content-Type"   "application/json"
                                                  "Accept"         "application/json"}
                            :query-params        params
                            :throw-exceptions    false
                            :socket-timeout      30000
                            :connection-timeout  10000}
                     body (assoc :body (json/generate-string body)))
            resp   (http/request req)
            status (:status resp)]
        (if (<= 200 status 299)
          {:ok (parse-body (:body resp))}
          {:error {:status status :body (parse-body (:body resp))}})))))

(defn get
  "HTTP GET. opts may contain :token and :params."
  [path opts]
  (request :get path nil opts))

(defn post
  "HTTP POST. opts may contain :token and :params."
  [path body opts]
  (request :post path body opts))
