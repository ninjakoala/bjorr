(ns ninjakoala.bjorr
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [io.logz.sender HttpsRequestConfiguration LogzioSender SenderStatusReporter]
           [io.logz.sender.exceptions LogzioParameterErrorException]
           [java.io File]
           [java.nio.charset StandardCharsets]))

(def ^:private debug-prefix
  "DEBUG: ")

(defn- debug?
  [message]
  (str/starts-with? message debug-prefix))

(defn- replace-debug
  [message]
  (str/replace message debug-prefix ""))

(defn- create-reporter
  []
  (reify
    SenderStatusReporter
    (error [this message]
      (log/error message))
    (error [this message throwable]
      (log/error throwable message))
    (warning [this message]
      (log/warn message))
    (warning [this message throwable]
      (log/warn throwable message))
    (info [this message]
      (if (debug? message)
        (log/debug (replace-debug message))
        (log/info message)))
    (info [this message throwable]
      (if (debug? message)
        (log/debug throwable (replace-debug message))
        (log/info throwable message)))))

(def ^:private this-namespace
  (str *ns*))

(def ^:private iso-format
  "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

(def ^:private default-sender-opts
  {:check-disk-space-interval-millis (* 1 1000)
   :compress-requests? false
   :connect-timeout-millis (* 1000 10)
   :debug? false
   :drain-timeout-seconds 5
   :file-system-full-percent-threshold 98
   :gc-persisted-queue-files-interval-seconds 30
   :in-memory-queue-capacity-bytes (* 100 1024 1024)
   :in-memory-queue-logs-count-limit nil
   :initial-wait-before-retry-millis (* 2 1000)
   :listener-url "https://listener.logz.io:8071"
   :log-type "java"
   :max-retries-attempts 3
   :queue-directory nil
   :request-method "POST"
   :socket-timeout-millis (* 1000 10)
   :use-in-memory-queue? false})

(def ^:private default-field-mappings
  {:?err :exception
   :?file :file
   :hostname_ :hostname
   :?line :line
   :level :loglevel
   :?ns-str :logger
   :msg_ :message
   :thread_ :thread
   :instant "@timestamp"})

(def ^:private default-log-opts
  {:add-file? false
   :add-hostname? false
   :add-line-number? false
   :add-thread-name? true
   :additional-fields nil
   :field-mappings default-field-mappings})

(defn- create-https-request-configuration
  [reporter {:keys [compress-requests? connect-timeout-millis initial-wait-before-retry-millis listener-url log-type max-retries-attempts request-method socket-timeout-millis token] :as opts}]
  (try
    (-> (HttpsRequestConfiguration/builder)
        (.setCompressRequests compress-requests?)
        (.setConnectTimeout connect-timeout-millis)
        (.setInitialWaitBeforeRetryMS initial-wait-before-retry-millis)
        (.setLogzioListenerUrl listener-url)
        (.setLogzioToken token)
        (.setLogzioType log-type)
        (.setMaxRetriesAttempts max-retries-attempts)
        (.setRequestMethod request-method)
        (.setSocketTimeout socket-timeout-millis)
        (.build))
    (catch LogzioParameterErrorException e
      (.error reporter "Failed to create HTTPS request configuration." e)
      nil)))

(defn- ensure-queue-directory
  [reporter queue-directory log-type]
  (let [type-directory (File. (str queue-directory File/separator log-type))]
    (if (.exists type-directory)
      (if-not (.canWrite type-directory)
        (do
          (.error reporter (format "Cannot write to queue-directory (%s)." (.getAbsolutePath type-directory)))
          nil)
        type-directory)
      (if-not (.mkdirs type-directory)
        (do
          (.error reporter (format "Cannot create queue-directory (%s)." (.getAbsolutePath type-directory)))
          nil)
        type-directory))))

(defn- queue-directory-from
  [reporter {:keys [log-type queue-directory] :as opts}]
  (let [queue-file (if queue-directory
                     (ensure-queue-directory reporter queue-directory log-type)
                     (str (System/getProperty "java.io.tmpdir") File/separator "logzio-logback-queue" File/separator log-type))]
    (when queue-file
      (File. queue-file "logzio-logback-appender"))))

(defn- validate-check-disk-space-interval-millis
  [reporter {:keys [check-disk-space-interval-millis] :as opts}]
  (if-not (and (some? check-disk-space-interval-millis) (pos? check-disk-space-interval-millis))
    (do
      (.warning reporter "check-disk-space-interval-millis must be a positive integer.")
      false)
    true))

(defn- validate-fs-percent-threshold
  [reporter {:keys [file-system-full-percent-threshold] :as opts}]
  (if-not (and (some? file-system-full-percent-threshold) (< 1 file-system-full-percent-threshold) (> 100 file-system-full-percent-threshold))
    (do
      (.warning reporter "file-system-full-percent-threshold must be between 1 and 100.")
      false)
    true))

(defn- validate-gc-persisted-queue-files-interval-seconds
  [reporter {:keys [gc-persisted-queue-files-interval-seconds] :as opts}]
  (if-not (and (some? gc-persisted-queue-files-interval-seconds) (pos? gc-persisted-queue-files-interval-seconds))
    (do
      (.warning reporter "gc-persisted-queue-files-interval-seconds must be a positive integer.")
      false)
    true))

(defn- with-disk-queue
  [builder reporter {:keys [check-disk-space-interval-millis file-system-full-percent-threshold gc-persisted-queue-files-interval-seconds] :as opts}]
  (when-let [queue-directory (queue-directory-from reporter opts)]
    (-> builder
        (.withDiskQueue)
        (.setCheckDiskSpaceInterval check-disk-space-interval-millis)
        (.setFsPercentThreshold file-system-full-percent-threshold)
        (.setGcPersistedQueueFilesIntervalSeconds gc-persisted-queue-files-interval-seconds)
        (.setQueueDir queue-directory)
        (.endDiskQueue))))

(defn- with-in-memory-queue
  [builder {:keys [in-memory-queue-capacity-bytes in-memory-queue-logs-count-limit] :as opts}]
  (-> builder
      (.withInMemoryQueue)
      (.setCapacityInBytes (or in-memory-queue-capacity-bytes -1))
      (.setLogsCountLimit (or in-memory-queue-logs-count-limit -1))
      (.endInMemoryQueue)))

(defn- validate-in-memory-thresholds
  [reporter {:keys [in-memory-queue-capacity-bytes in-memory-queue-logs-count-limit] :as opts}]
  (and (if (and (some? in-memory-queue-capacity-bytes) (not (pos? in-memory-queue-capacity-bytes)))
         (do
           (.warning reporter "in-memory-queue-capacity-bytes must be a positive integer.")
           false)
         true)
       (if (and (some? in-memory-queue-logs-count-limit) (not (pos? in-memory-queue-logs-count-limit)))
         (do
           (.warning reporter "in-memory-queue-logs-count-limit must be a positive integer.")
           false)
         true)))

(defn- with-queue
  [builder reporter {:keys [use-in-memory-queue?] :as opts}]
  (if use-in-memory-queue?
    (when (validate-in-memory-thresholds reporter opts)
      (.info reporter "Using in memory queue")
      (with-in-memory-queue builder opts))
    (when (and (validate-check-disk-space-interval-millis reporter opts)
               (validate-fs-percent-threshold reporter opts)
               (validate-gc-persisted-queue-files-interval-seconds reporter opts))
      (.info reporter "Using disk queue")
      (with-disk-queue builder reporter opts))))

(defn create-logzio-sender
  ([token threadpool]
   (create-logzio-sender token threadpool nil))
  ([token threadpool opts]
   (let [required-config {:threadpool threadpool
                          :token token}
         {:keys [debug? drain-timeout-seconds threadpool] :as opts} (merge default-sender-opts opts required-config)
         reporter (create-reporter)]
     (when-let [request-configuration (create-https-request-configuration reporter opts)]
       (when-let [builder (try
                            (-> (LogzioSender/builder)
                                (.setDebug debug?)
                                (.setDrainTimeoutSec drain-timeout-seconds)
                                (.setHttpsRequestConfiguration request-configuration)
                                (.setReporter reporter)
                                (.setTasksExecutor threadpool)
                                (with-queue reporter opts))
                            (catch LogzioParameterErrorException e
                              (.error reporter "Could not create Logzio sender." e)
                              (.shutdownNow threadpool)
                              nil))]
         (.build builder))))))

(defn- current-thread-name
  []
  (.getName (Thread/currentThread)))

(defn- log->json-line
  [opts log]
  (let [opts (-> (merge default-log-opts opts)
                 (update :field-mappings (partial merge default-field-mappings)))
        {:keys [add-file? add-hostname? add-line-number? add-thread-name? additional-fields field-mappings]} opts
        log-object (->> (merge (:context log)
                               {:?err (some-> (:?err log) (log/stacktrace {:stacktrace-fonts {}}))
                                :?file (when add-file? (:?file log))
                                :hostname_ (when add-hostname? (force (:hostname_ log)))
                                :?line (when add-line-number? (:?line log))
                                :level (str/upper-case (name (:level log)))
                                :?ns-str (:?ns-str log)
                                :msg_ (force (:msg_ log))
                                :instant (:instant log)
                                :thread_ (when add-thread-name? (current-thread-name))}
                               additional-fields)
                        (remove (fn [[k v]] (nil? v)))
                        (into {}))]
    (-> log-object
        (set/rename-keys field-mappings)
        (json/generate-string {:date-format iso-format
                               :pretty false}))))

(defn- handle-log
  [sender opts log]
  (let [json-line (log->json-line opts log)]
    (.send sender (.getBytes json-line StandardCharsets/UTF_8))))

(defn create-logzio-appender
  ([sender]
   (create-logzio-appender sender nil))
  ([sender opts]
   {:async? false
    :enabled? (some? sender)
    :fn (partial handle-log sender opts)
    :min-level nil
    :ns-blacklist [this-namespace "io.logz.sender.*"]
    :output-fn :inherit
    :rate-limit nil}))
