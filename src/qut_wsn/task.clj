(ns qut-wsn.task
  (:gen-class)
  
  (:use [qut-wsn.control])
  
  (:use [clojure.java.shell :only [sh]])
  (:use [clojure.string :only [trim]])
  (:use [clojure.contrib.math :only [abs]])
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

  (:require [taoensso.nippy :as nippy]))

(defn seconds-remaining
  "Returns the number of seconds (to three places) until the next minute begins for
   the given time."
  [the-time]
  (let [next-minute (.withMillisOfSecond (.withSecondOfMinute (.plusMinutes the-time 1) 0) 0)
        record-time (in-millis (interval the-time next-minute))]
    (/ record-time 1000.0)))

(defn filepath
  [filename]
  (.getPath (clojure.java.io/file filename)))

(defn replace-ext
  [filepath ext]
  (clojure.string/replace filepath #"(\w+)\.(\w+)$" (str "$1." ext)))

(defn time-as-filepath
  [the-time suffix]
  (let [this-minute (.withMillisOfSecond (.withSecondOfMinute the-time 0) 0)
        time-formatter (formatters :date-hour-minute)]
    (format "%s.%s" (unparse time-formatter this-minute) suffix)))

(defn record-audio
  [sample-rate bit-rate channels]
  (let [time-now (local-now)
        duration (seconds-remaining time-now)
        filename (time-as-filepath time-now "wav")
        command (format "rec -q -r %s -b %s -c %s %s trim 0 %s" sample-rate bit-rate channels filename duration)]
    (local-exec command)
    (filepath filename)))

(defn spectrogram
  [wave-filepath fft-sample-size overlap]
  (let [spec-data (.getNormalizedSpectrogramData (.getSpectrogram (Wave. wave-filepath) fft-sample-size overlap))]            
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

(comment
  (defn archive
    [archive-folder max-folder-size filepath]
    (let [folder-size (FileUtils/sizeOfDirectory (clojure.java.io/file archive-folder))
          file-size (FileUtils/sizeOf (clojure.java.io/file filepath))]
      (loop [folder-size (FileUtils/sizeOfDirectiry (clojure.java.io/file archive-folder))]
        (if (> folder-size max-folder-size)
                                        ; delete oldest file
          )
        (recur (FileUtils/sizeOfDirectory (clojure.java.io/file archive-folder)))))))

(defn move-file
  [filepath destination]
  (FileUtils/moveToDirectory (file filepath) (file destination) true)
  (.getPath (file destination (.getName (file filepath)))))

; folder size eg. (FileUtils/sizeOfDirectory (clojure.java.io/file "/home/luke/uni"))

(defn hidden-file?
  [file]
  (= (first (.getName file)) \.))

(defn compare-files
  [file1 file2]
  (let [mod1 (from-long (.lastModified file1))
        mod2 (from-long (.lastModified file2))]
    (compare mod1 mod2)))

(defn oldest-files
  "Returns a list of files in folder sorted by oldest last modified time."
  [folder]
  (let [file-list (aclone (.listFiles (clojure.java.io/file folder)))]
    (reverse (map #(.getPath %) (sort compare-files (filter #(not (hidden-file? %)) file-list))))))
