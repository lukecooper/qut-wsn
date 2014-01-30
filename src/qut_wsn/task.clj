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

  (:import [org.apache.commons.io FileUtils])
  (:import [java.nio ByteBuffer])
  (:import [com.musicg.wave Wave])
  (:import [com.musicg.wave.extension Spectrogram]))

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

(declare write-array)
(defn spectrogram
  [filepath fft-sample-size overlap]
  (let [spec-filepath (clojure.string/replace filepath #"(\w+)\.(\w+)$" "$1.spec")]
    (write-array spec-filepath
                 (.getAbsoluteSpectrogramData (.getSpectrogram (Wave. filepath) fft-sample-size overlap)))
    spec-filepath))

(defn delta
  [[val & coll]]
  (if (empty? coll)
    val
    (+ (abs (- val (first coll)))
       (delta coll))))

;; this is incorrect, it is accumulating across frames
;; whereas it should be across frequency bins, boo :(
(defn calculate-aci
  [spectrogram]
  (into-array Double/TYPE
    (map (fn [frame]
           (let [sum (reduce + frame)
                 delta (delta frame)]
             (if (> sum 0) (/ delta sum))))
         spectrogram)))

(declare read-array)
(defn aci
  [spec-filepath]
  (let [aci-filepath (clojure.string/replace spec-filepath #"(\w+)\.(\w+)$" "$1.aci")]
    (->> spec-filepath
        (read-array)
        ;(calculate-aci)
        ;(write-array aci-filepath)
        )
    ;aci-filepath
    ))

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

(defn write-array
  [filepath array]
  (let [x-len (alength array)
        y-len (alength (aget array 0))
        buf-len (+ 4 4 (* x-len y-len 8))
        byte-buffer (ByteBuffer/allocate buf-len)
        buffer (byte-array buf-len)]
    (do      
      (.putInt byte-buffer x-len)
      (.putInt byte-buffer y-len)
      (doseq [x (range x-len)
              y (range y-len)]
        (.putDouble byte-buffer
                    ; fully hinted for performace
                    (let [#^doubles a (aget #^objects array x)]
                      (aget a y))))
      (.flip byte-buffer)
      (.get byte-buffer buffer)
      (with-open [out (output-stream (file filepath))]
        (.write out buffer)))))

(defn read-array-size
  [filepath]
  (with-open [in (input-stream filepath)]
    (let [buffer (byte-array 8)
          read-bytes (.read in buffer 0 8)
          byte-buffer (ByteBuffer/allocate read-bytes)]
      (.put byte-buffer buffer 0 read-bytes)
      (.flip byte-buffer)
      (let [x-len (.getInt byte-buffer)
            y-len (.getInt byte-buffer)]
        [x-len y-len]))))

(defn read-array
  [filepath]
  (let [[x-len y-len] (read-array-size filepath)
        array (make-array Double/TYPE x-len y-len)]
    (with-open [in (input-stream filepath)]
      (let [buffer-size (+ 4 4 (* x-len y-len 8))
            buffer (byte-array buffer-size)
            read-bytes (.read in buffer 0 buffer-size)
            byte-buffer (ByteBuffer/allocate read-bytes)]
        (.put byte-buffer buffer 0 read-bytes)
        (.flip byte-buffer)
        (.getInt byte-buffer) ; x-len
        (.getInt byte-buffer) ; y-len
        (doseq [x (range x-len)
                y (range y-len)]
          ; fully hinted for performance
          (let [#^doubles a (aget #^objects array x)]
            (aset a y (.getDouble byte-buffer))))
        array))))
