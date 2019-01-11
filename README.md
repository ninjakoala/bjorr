[![Clojars Project](https://img.shields.io/clojars/v/com.ninjakoala/bjorr.svg)](https://clojars.org/com.ninjakoala/bjorr) [![Build Status](https://travis-ci.org/ninjakoala/bjorr.svg?branch=master)](https://travis-ci.org/ninjakoala/bjorr)

# bjorr

[](dependency)
```clojure
[com.ninjakoala/bjorr "0.1.0-SNAPSHOT"] ;; latest release
```
[](/dependency)

A [Timbre](https://github.com/ptaoussanis/timbre) appender for [Logz.io](https://logz.io) which uses their [Java sender](https://github.com/logzio/logzio-java-sender) and is based on their [Logback appender](https://github.com/logzio/logzio-logback-appender).

## Usage

Just to demonstrate the steps required to get up and running in a simple app:

```clojure
(ns your.app
  (:require [ninjakoala.bjorr :as bjorr]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Executors]))

(defn -main
  []
  ;; I'd recommend blacklisting these two patterns in Timbre unless you desperately want them.
  ;; They're both only really useful if you're having trouble getting the sender or appender
  ;; up and running. It's also a good idea to blacklist them _before_ starting the sender.
  (log/merge-config! {:ns-blacklist ["io.logz.sender.*" "ninjakoala.bjorr"]})
  (let [threadpool (Executors/newScheduledThreadPool 3)
        sender (bjorr/create-logzio-sender "<YOUR_LOGZIO_TOKEN>" threadpool)
        appender (bjorr/create-logzio-appender sender)]
    (.start sender)
    (log/merge-config! {:appenders {:bjorr appender}})
    (log/with-context {:some "context"
                       :look-numbers 123}
      (log/warn "Hello from Bjorr"))
    (.stop sender)
    (.shutdown threadpool)))
```

This will result in the following JSON being sent to Logz.io (with the default type of `java`):

```json
{
  "@timestamp": "2019-01-11T16:51:34.396Z",
  "logger": "your.app",
  "loglevel": "warn",
  "look-numbers": 123,
  "message": "Hello from Bjorr",
  "some": "context",
  "thread": "main",
}
```

Of course, you can use [Claypoole](https://github.com/TheClimateCorporation/claypoole) for your threadpools. It'll just slot in as `threadpool` in the above example.

Using [Integrant](https://github.com/weavejester/integrant) you might start things up like this:

```clojure
(ns your.app
  (:require [integrant.core :as ig]
            [ninjakoala.bjorr :as bjorr]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Executors]))

(def config
  {::logzio-threadpool {:thread-count 3}
   ::logzio-sender {:threadpool (ig/ref ::logzio-threadpool)
                    :token "<YOUR_LOGZIO_TOKEN>"}
   ::logzio-appender {:sender (ig/ref ::logzio-sender)}
   ::logging-config {:appenders {:bjorr (ig/ref ::logzio-appender)}}})

(defmethod ig/init-key ::logzio-threadpool
  [_ {:keys [thread-count]}]
  (Executors/newScheduledThreadPool thread-count))

(defmethod ig/halt-key! ::logzio-threadpool
  [_ threadpool]
  (when threadpool
    (.shutdown threadpool)))

(defmethod ig/init-key ::logzio-sender
  [_ {:keys [threadpool token]}]
  (let [sender (bjorr/create-logzio-sender token threadpool)]
    (.start sender)
    sender))

(defmethod ig/halt-key! ::logzio-sender
  [_ sender]
  (when sender
    (.stop sender)))

(defmethod ig/init-key ::logzio-appender
  [_ {:keys [sender]}]
  (bjorr/create-logzio-appender sender))

(defmethod ig/init-key ::logging-config
  [_ config]
  (log/merge-config! config))

(defn -main
  []
  (log/merge-config! {:ns-blacklist ["io.logz.sender.*" "ninjakoala.bjorr"]})
  (when-let [system (ig/init config)]
    (log/with-context {:some "context"
                       :look-numbers 123}
      (log/warn "Hello from Bjorr"))
    (ig/halt! system)))
```

Or using [Mount](https://github.com/tolitius/mount):

```clojure
(ns your.app
  (:require [mount.core :as mount :refer [defstate]]
            [ninjakoala.bjorr :as bjorr]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Executors]))

(defstate logzio-threadpool
  :start (Executors/newScheduledThreadPool 3)
  :stop (when logzio-threadpool
          (.shutdown logzio-threadpool)))

(defstate logzio-sender
  :start (let [sender (bjorr/create-logzio-sender "<YOUR_LOGZIO_TOKEN>" logzio-threadpool)]
           (.start sender)
           sender)
  :stop (when logzio-sender
          (.stop logzio-sender)))

(defstate logzio-appender
  :start (bjorr/create-logzio-appender logzio-sender))

(defstate logging
  :start (log/merge-config! {:appenders {:bjorr logzio-appender}})
  :stop (log/swap-config! update-in [:appenders] #(dissoc % :bjorr)))

(defn -main
  []
  (log/merge-config! {:ns-blacklist ["io.logz.sender.*" "ninjakoala.bjorr"]})
  (mount/start)
  (log/with-context {:some "context"
                     :look-numbers 123}
    (log/warn "Hello from Bjorr"))
  (mount/stop))
```

### Configuration

#### Sender

The defaults used by the Logback appender are in place in this library but you can customise the following settings to suit your needs. For descriptions of the settings it's probably best to head over to the [Parameters](https://github.com/logzio/logzio-java-sender#parameters) section of the original sender's README.

Key | Default value
--- | ---
`:check-disk-space-interval-millis` | `1000`
`:compress-requests?` | `false`
`:connect-timeout-millis` | `10000`
`:debug?` | `false`
`:drain-timeout-seconds` | `5`
`:file-system-full-percent-threshold` | `98`
`:gc-persisted-queue-files-interval-seconds` | `30`
`:in-memory-queue-capacity-bytes` | `104857600`
`:in-memory-queue-logs-count-limit` | `nil`
`:initial-wait-before-retry-millis` | `2000`
`:listener-url` | `https://listener.logz.io:8071`
`:log-type` | `java`
`:max-retries-attempts` | `3`
`:queue-directory` | `nil`
`:request-method` | `POST`
`:socket-timeout-millis` | `10000`
`:use-in-memory-queue?` | `false`

These options are specified as the optional third argument to `ninjakoala.bjorr/create-logzio-sender`. So to use an in-memory queue you would make the following call:

```clojure
(bjorr/create-logzio-sender "<YOUR_LOGZIO_TOKEN>" threadpool {:use-in-memory-queue? true})
```

#### Appender

Finally there are a number of options you can use to configure the appender itself.

Key | Default value | Description
--- | --- | ---
`:add-file?` | `false` | Whether to add the filename of the log message.
`:add-hostname?` | `false` | Whether to add the hostname of the machine hosting this JVM.
`:add-line-number?` | `false` | Whether to add the line number of the log statement.
`:add-thread-name?` | `true` | Whether to add the name of the calling thread.
`:additional-fields` | `nil` | A map containing a number of static fields which will be merged into the context of every log message (therefore appearing as top-level fields in the JSON object sent to Logz.io). For example, you might want to log the service name, datacenter or availability zone.
| `:field-mappings` | `default-field-mappings` | See the [Field mappings](#field-mappings) section in the table below.

These options are specified as the optional second argument to `ninjakoala.bjorr/create-logzio-appender`. So to stop adding the thread name to each log message you would make the following call:

```clojure
(bjorr/create-logzio-appender sender {:add-thread-name? false})
```

#### Field mappings

The original Logback appender defines some field names which have been adopted by this library as defaults. However, it's all configurable so you can name things what you want.

Timbre field | Logz.io field
--- | ---
`:?err` | `:exception`
`:?file` | `:file`
`:hostname_` | `:hostname`
`:?line` | `:line`
`:level` | `:loglevel`
`:?ns-str` | `:logger`
`:msg_` | `:message`
`:thread_` | `:thread`
`:instant` | `"@timestamp"`

These are specified as the `:field-mappings` key in the configuration of the appender. So, to change the `:hostname_` field to be called `:pod-name` you would create the appender with the following call:

```clojure
(bjorr/create-logzio-appender sender {:field-mappings {:hostname_ :pod-name}})
```

## License

Copyright © 2019 Neil Prosser

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.