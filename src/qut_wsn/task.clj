(ns qut-wsn.task
  (:gen-class)
  
  (:use [qut-wsn.control])
  (:use [qut-wsn.config])
  
  (:use [clojure.java.shell :only [sh]])
  (:use [clojure.string :only [trim]])
  (:use [clojure.contrib.math :only [abs ceil]])
  (:use [clojure.java.io :only [file output-stream input-stream]])
  
  (:use [clj-time.core :only [interval in-millis]])
  (:use [clj-time.local :only [local-now]])
  (:use [clj-time.format :only [formatters unparse]])
  (:use [clj-time.coerce :only [to-long from-long]])  
  
  (:import [com.musicg.wave Wave])
  (:import [com.musicg.wave.extension Spectrogram])
  (:import [com.musicg.graphic GraphicRender])
  (:import [edu.qut.wsn ACI])
  (:import [java.nio ByteBuffer])
  (:import [org.apache.commons.io FileUtils])

  (:require [taoensso.nippy :as nippy])
  (:require [taoensso.timbre :as timbre]))

;; include logging
(timbre/refer-timbre)
(timbre/set-config! [:appenders :spit :enabled?] true)
(timbre/set-config! [:shared-appender-config :spit-filename] "log/wsn.log")

(defn seconds-remaining
  "Returns the number of seconds (to three places) until the next minute begins for
   the given time."
  [the-time]
  (let [next-minute (.withMillisOfSecond (.withSecondOfMinute (.plusMinutes the-time 1) 0) 0)
        record-time (in-millis (interval the-time next-minute))]
    (/ record-time 1000.0)))

(defn filepath
  [filename]
  (.getAbsolutePath (clojure.java.io/file filename)))

(defn replace-ext
  [filepath ext]
  (clojure.string/replace filepath #"(\w+)\.(\w+)$" (str "$1." ext)))

(defn time-as-filepath
  [the-time suffix]
  (let [this-minute (.withMillisOfSecond (.withSecondOfMinute the-time 0) 0)
        time-formatter (formatters :date-hour-minute)]
    (format "%s.%s" (unparse time-formatter this-minute) suffix)))

(defn time-as-seconds
  [the-time]
  (/ (.get (.millisOfDay the-time)) 1000.0))

(defn record-audio
  [sample-rate bit-rate channels]
  (let [time-now (local-now)
        filename (time-as-filepath time-now "wav")]
    (local-exec (format "rec -q -r %s -b %s -c %s %s trim 0 %s"
                        sample-rate bit-rate channels filename
                        (seconds-remaining time-now)))
    (filepath filename)))

(defn fake-record-audio
  [source sample-rate bit-rate channels]
  (let [time-now (local-now)
        filename (time-as-filepath time-now "wav")]
    (info (time-as-seconds time-now))
    (info (seconds-remaining time-now))
    (local-exec (format "sox  -r %s -b %s -c %s %s %s trim %s %s"
                        sample-rate bit-rate channels source filename
                        (time-as-seconds time-now)
                        (seconds-remaining time-now)))
    (Thread/sleep (* (seconds-remaining (local-now)) 1000.0))
    (filepath filename)))

(defn spectrogram
  [wave-filepath fft-sample-size overlap]
  (let [spec-data (.getNormalizedSpectrogramData
                   (.getSpectrogram (Wave. wave-filepath) fft-sample-size overlap))]
    (with-open [out (output-stream (file (replace-ext wave-filepath "spec")))]      
      (.write out (nippy/freeze spec-data))))
  (replace-ext wave-filepath "spec"))

(defn render-spectrogram
  [spec-filepath]
  (let [buffer (byte-array (.length (file spec-filepath)))]
    (with-open [in (input-stream (file spec-filepath))]
      (.read in buffer))
    (.renderSpectrogramData (GraphicRender.) (nippy/thaw buffer) (replace-ext spec-filepath "png")))
  (replace-ext spec-filepath "png"))

(defn aci
  [spec-filepath]
  (let [buffer (byte-array (.length (file spec-filepath)))]
    (with-open [in (input-stream (file spec-filepath))]
      (.read in buffer))    
    (with-open [out (output-stream (file (replace-ext spec-filepath "aci")))]
      (.write out (nippy/freeze (ACI/calculateACI (nippy/thaw buffer)))))
    (replace-ext spec-filepath "aci")))

(defn list-files-by-age
  [directory]
  (clojure.string/split (local-exec (format "ls -cr %s" directory)) #"\n"))

(defn directory-size
  [directory]
  (let [result (re-seq #"[0-9]+" (local-exec (format "du -S --summarize %s" directory)))
        kb-size (if-not (nil? result) (read-string (first result)) 0)]
    (if (> kb-size 0)
      (int (ceil (/ kb-size 1000.0)))
      0)))

(defn cleanup-directory
  [directory size-limit]
  (let [file-list (list-files-by-age directory)]
    (loop [[oldest & remainder] file-list]
      (when (and (not (nil? oldest))
                 (> (directory-size directory) size-limit))
        (let [file-to-delete (file directory oldest)]
          (when (.isFile file-to-delete)
            (info "Deleting file" (.getPath file-to-delete))
            (.delete file-to-delete)))
        (recur remainder)))))

(defn move-file
  [filepath destination]
  (FileUtils/moveToDirectory (file filepath) (file destination) true)
  (.getAbsolutePath (file destination (.getName (file filepath)))))

(defn copy-remote-file
  [hostname filepath destination]
  (let [filename (.getName (file filepath))
        local-file (file destination (str hostname "-" filename))]
    (info "Copying" filepath "from" hostname "to" (.getPath local-file))
    (FileUtils/forceMkdir (file destination))
    (local-exec (get-path filepath (.getPath local-file) (host-value hostname :user) (host-value hostname :address)))
    (.getAbsolutePath local-file)))


(defn update-network
  [network-config]
  (let [new-config (find-host (hostname) (read-string network-config))]
    (save-network-config new-config)
    (pr-str new-config)))

(defn stop
  []
  "stop")
