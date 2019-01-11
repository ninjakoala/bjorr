(ns ninjakoala.bjorr-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [ninjakoala.bjorr :as sut]
            [taoensso.timbre :as log]))

(def default-log
  {:?err "err"
   :?file "file"
   :hostname_ (delay "hostname")
   :?line "line"
   :level "level"
   :?ns-str "ns-str"
   :msg_ (delay "msg")
   :instant "instant"})

(def default-json
  {"@timestamp" "instant"
   "exception" "stacktrace of err"
   "logger" "ns-str"
   "loglevel" "level"
   "message" "msg"
   "thread" "thread"})

(deftest log->json-line
  (with-redefs [log/stacktrace (fn [e _] (str "stacktrace of " e))
                sut/current-thread-name (constantly "thread")]
    (testing "Default options includes particular fields"
      (is (= default-json
             (-> (#'sut/log->json-line {} default-log)
                 json/parse-string))))
    (testing "We add the file when asked"
      (is (= (assoc default-json "file" "file")
             (-> (#'sut/log->json-line {:add-file? true} default-log)
                 json/parse-string))))
    (testing "We add the hostname when asked"
      (is (= (assoc default-json "hostname" "hostname")
             (-> (#'sut/log->json-line {:add-hostname? true} default-log)
                 json/parse-string))))
    (testing "We add the line number when asked"
      (is (= (assoc default-json "line" "line")
             (-> (#'sut/log->json-line {:add-line-number? true} default-log)
                 json/parse-string))))
    (testing "We exclude the thread name when asked"
      (is (= (dissoc default-json "thread")
             (-> (#'sut/log->json-line {:add-thread-name? false} default-log)
                 json/parse-string))))
    (testing "Context fields are included at the top-level"
      (is (= (assoc default-json
                    "field-one" "value"
                    "field-two" 123)
             (-> (#'sut/log->json-line {} (assoc default-log :context {:field-one "value"
                                                                       :field-two 123}))
                 json/parse-string))))
    (testing "Additional fields are included at the top-level"
      (is (= (assoc default-json
                    "field-one" "value"
                    "field-two" 123)
             (-> (#'sut/log->json-line {:additional-fields {:field-one "value"
                                                            "field-two" 123}} default-log)
                 json/parse-string))))
    (testing "We can change the name of a field"
      (is (= (-> default-json
                 (dissoc "message")
                 (assoc "my-message" "msg"))
             (-> (#'sut/log->json-line {:field-mappings {:msg_ :my-message}} default-log)
                 json/parse-string))))))
