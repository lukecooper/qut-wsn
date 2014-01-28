(ns qut-wsn.core
  (:gen-class)
  (:import [org.jeromq ZMQ])
  (:use [clojure.java.shell :only [sh]])
  (:use [clojure.string :only [trim]])
  (:use [clojure.contrib.math :only [abs]])
  (:use [qut-wsn.control])
  (:use [clj-time.core :only [interval in-millis]]
        [clj-time.local :only [local-now]]
        [clj-time.format :only [formatters unparse]]
        [clj-time.coerce :only [to-long from-long]])
  (:use [clojure.java.io :only [file output-stream input-stream]])
  (:import [com.musicg.wave Wave])
  (:import [com.musicg.wave.extension Spectrogram])
  (:import [org.apache.commons.io FileUtils])
  (:import [java.nio ByteBuffer]))

(def ^:const task-listen-port 47687)
(def ^:const task-publish-port 47688)
(def ^:const status-listen-port 47689)

(def startup-tasks
  [{:name "record"
    :exec "record"
    :repeat true
    :params "[sample-rate bit-rate channels]"}
   {:name "audio-archive"
    :exec "archive"
    :depends "record"
    :params "[folder]"}
   {:name "audio-cleanup"
    :exec "cleanup"
    :depends "audio-archive"
    :params "[max-folder-size]"}
   {:name "spectrogram"
    :exec "spectrogram"
    :depends "audio-archive"
    :params "[window-size overlap]"}
   {:name "spectrogram-archive"
    :exec "archive"
    :depends "spectrogram"
    :params "[folder]"}
   {:name "spectrogram-cleanup"
    :exec "cleanup"
    :params "[max-folder-size]"}
   {:name "error-handler"
    :exec "error-handler"
    :depends "error"}])

(def sample-query
  {:name "aci" ; acoustic complexity index
   :id 234 ; added at runtime
   :tasks [{:name "aci"
            :exec "aci"
            :depends "spectrogram-archive"
            :query-name "aci" ; added at runtime
            :query-id 234 ; added at runtime
            }
           {:name "aci-results"
            :exec "results"
            :params "[address port]" ; address and port to send results to
            :depends "aci"
            :query-name "aci" ; added at runtime
            :query-id 234 ; added at runtime
            }
           {:name "aci-collate"
            :exec "aci-collate"
            :params "[port]" ; port to listen on for results
            }]})


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
  [folder the-time suffix]
  (let [this-minute (.withMillisOfSecond (.withSecondOfMinute the-time 0) 0)
        time-formatter (formatters :date-hour-minute)]
    (format "%s/%s.%s" folder (unparse time-formatter this-minute) suffix)))

(defn record-audio
  [sample-rate bit-rate channels destination]
  (let [time-now (local-now)
        duration (seconds-remaining time-now)
        filename (time-as-filepath destination time-now "wav")
        command (format "rec -q -r %s -b %s -c %s %s trim 0 %s" sample-rate bit-rate channels filename duration)]
    (local-exec command)
    (filepath filename)))

(defn spectrogram
  [input-file fft-sample-size overlap]
  (let [spectrogram (.getSpectrogram (Wave. input-file) fft-sample-size overlap)]
    (.getAbsoluteSpectrogramData spectrogram)))

(defn delta
  [[val & coll]]
  (if (empty? coll)
    val
    (+ (abs (- val (first coll)))
       (delta coll))))

(defn aci
  [spectrogram]
  (into-array Double/TYPE
    (map (fn [frame]
           (let [sum (reduce + frame)
                 delta (delta frame)]
             (if (> sum 0) (/ delta sum))))
     spectrogram)))

(declare write-array)

(defn write-spectrogram
  [input-file spectrogram-data]
  (let [spectrogram-filepath (clojure.string/replace input-file #"(\w+)\.(\w+)$" "$1.spec")]
    (write-array spectrogram-data spectrogram-filepath)))

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
  [array filename]
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
      (with-open [out (output-stream (file filename))]
        (.write out buffer)))))

(defn read-array-size
  [filename]
  (with-open [in (input-stream filename)]
    (let [buffer (byte-array 8)
          read-bytes (.read in buffer 0 8)
          byte-buffer (ByteBuffer/allocate read-bytes)]
      (.put byte-buffer buffer 0 read-bytes)
      (.flip byte-buffer)
      (let [x-len (.getInt byte-buffer)
            y-len (.getInt byte-buffer)]
        [x-len y-len]))))

(defn read-array
  [filename]
  (let [[x-len y-len] (read-array-size filename)
        array (make-array Double/TYPE x-len y-len)]
    (with-open [in (input-stream filename)]
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

(defn hostname
  []
  (trim (:out (sh "hostname"))))

(comment
  as qut-wsn-prime
  read node-tree
  read node-map
  make new node tree with bound ip addresses
  
  host
  i get a node-def with a host name, role and list of child nodes
  i store my role and my list of child nodes
  i iterate through my list of child nodes and give them their node-def)

;; on wsn-prime startup
;;   if config files exist
;;     read 'em
;;     configure me
;;     execute configure task on my children (i know how to contact
;;   them now)

;; on qut-rpi-0xx startup
;;   no config files exist
;;   start task listener
;;   receive configure task with node-def
;;   configure me
;;   execute configure task on my children



(defn merge-addresses
  [node-def node-map]
  (let [node-address ((keyword (:name node-def)) node-map)
        merged-def (merge node-def {:address node-address})]
    (if (contains? merged-def :nodes)
      (merge merged-def {:nodes (mapv #(merge-addresses % node-map) (:nodes merged-def))})
      merged-def)))

(defn read-network-conf
  [network-structure-filename hostname-lookup-filename]
  (let [network-structure (read-string (slurp network-structure-filename))
        hostname-lookup (read-string (slurp hostname-lookup-filename))]
    (merge-addresses network-structure hostname-lookup)))

(def node-tree
  (read-network-conf "network-structure.clj" "hostname-lookup.clj"))

(declare configure-node)

(defn configure-nodes
  [nodelist]
  (map configure-node nodelist))

(defn configure-node
  [nodedef]
  (let [name (:name nodedef)
        role (:role nodedef)]  
    (if (contains? nodedef :nodes)
      (configure-nodes (:nodes nodedef)))))

(def ctx (ZMQ/context 1))


(defn append-filter
  [filter message]
  (clojure.string/join (list filter ":" message)))

(defn remove-filter
  [message]
  (second (clojure.string/split message #":" 2)))

(defn publish
  [socket message filter]
  (.send socket (append-filter filter message)))

(defn wait-for
  [task]
  (let [socket (.socket ctx ZMQ/SUB)
        filter (str task)]
    (.connect socket "tcp://127.0.0.1:8888")
    (.subscribe socket filter)
    (let [message (String. (.recv socket))]
      (.close socket)
      (remove-filter message))))

(defn exec-task
  [task-defn]
  (if (nil? (:depends task-defn))
    ((:exec task-defn))
    (let [input (wait-for (:depends task-defn))]
      ((:exec task-defn) input))))

(defn run-task  
  [task-defn publish-socket]
  (loop []
    (let [result (exec-task task-defn)]
      (println result)
      (publish publish-socket result (:name task-defn)))
    (if (:repeat task-defn)
      (recur))))

(defn task-handler
  [publish message respond]
  (printf message)
  (.send respond (format "task OK - %s" message)))

(defn listener
  [address port handler]
  (let [listen-socket (.socket ctx ZMQ/REP)]
    (.bind listen-socket (format "tcp://%s:%s" address port))
    (loop [message (String. (.recv listen-socket))]
      (handler message listen-socket)
      (recur (String. (.recv listen-socket))))))

(defn publisher
  [address port]
  (let [publish-socket (.socket ctx ZMQ/PUB)]
    (.bind publish-socket (format "tcp://%s:%s" address port))
    publish-socket))

(defn -main
  [& args]
  (let [local-address (qut-wsn.control/host-address "localhost")
        publish-socket (publisher local-address task-publish-port)
        task-listener (future (listener local-address task-listen-port (partial task-handler publish-socket)))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (.close publish-socket))))
    @task-listener))

(defn test-listener
  []
  (let [local-address (qut-wsn.control/host-address "localhost")
        publish-socket (publisher local-address task-publish-port)]
    (listener local-address task-listen-port (partial task-handler publish-socket))))

(defn test-tasker
  [hostname message]
  (let [socket (.socket ctx ZMQ/REQ)]
    (.connect socket (format "tcp://%s:%s" (qut-wsn.control/host-address hostname) task-listen-port))
    (.send socket message)
    (let [response (String. (.recv socket))]
      (.close socket)
      response)))

;; (run-task "configure" node-tree)
;; lookup task
;;   {:name "configure"
;;    :exec "configure"}
;; encode args
;; call function configure
;;   (configure node-tree)

;; (run-task "task" arg1 arg2 argn)

(defn run-task
  "task|arg1|arg2|arg3"
  [task-name & args]
  (let [nodes (:nodes node-tree)
        message (clojure.string/join "|" (cons task-name (map pr-str args)))]
    message))

