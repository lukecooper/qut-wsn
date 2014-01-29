(ns qut-wsn.core
  (:gen-class)
  (:use [qut-wsn.control])
  (:use [qut-wsn.task])  
  (:use [clojure.java.shell :only [sh]])
  (:use [clojure.string :only [trim split join]])  
  (:use [clojure.java.io :only [file]])
  (:import [org.jeromq ZMQ])
  (:import [org.apache.commons.io FileUtils]))

(def ^:const task-listen-port 47687)
(def ^:const task-publish-port 47688)
(def ^:const status-listen-port 47689)

(defn hostname
  []
  (trim (:out (sh "hostname"))))

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
  (if (and (.exists (file network-structure-filename))
           (.exists (file hostname-lookup-filename)))
    (let [network-structure (read-string (slurp network-structure-filename))
          hostname-lookup (read-string (slurp hostname-lookup-filename))]
      (merge-addresses network-structure hostname-lookup))
    {}))

(def node-tree
  (read-network-conf "network-structure.clj" "hostname-lookup.clj"))

(def ctx (ZMQ/context 1))

(defn append-filter
  [filter message]
  (join (list filter ":" message)))

(defn remove-filter
  [message]
  (second (split message #":" 2)))

(defn publish
  [socket filter message]
  (.send socket (append-filter filter message)))

(defn wait-for
  [task-name]
  (let [socket (.socket ctx ZMQ/SUB)
        filter (str task-name)]
    (.connect socket (format "tcp://%s:%s" (host-address "localhost") task-publish-port))
    (.subscribe socket filter)
    (let [message (String. (.recv socket))]
      (.close socket)
      (remove-filter message))))

(defn call-task-fn
  ([task-def]
     (apply (resolve (symbol "qut-wsn.task" (:call task-def))) (:params task-def)))
  ([task-def input]
     (apply (resolve (symbol "qut-wsn.task" (:call task-def))) (cons input (:params task-def)))))

(defn start-task
  [task-def publish-socket]
  (let [dependency (:depends task-def)
        repeat? (:repeat task-def)]
    (loop []
      (->> (if (nil? dependency)
             (call-task-fn task-def)
             (call-task-fn task-def (wait-for dependency)))
           (publish publish-socket (:name task-def)))
      (if repeat?
        (recur)))))

(defn decode-task
  [message]
  (read-string message))

(defn task-handler
  [publish message respond]
  (let [task-def (decode-task message)]
    (future (start-task task-def publish))
    (.send respond (String. "OK"))))

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

(defn send-message
  "Synchronously sends a string message to the given address and returns the response."
  [address port message]  
  (let [socket (.socket ctx ZMQ/REQ)
        sock-addr (format "tcp://%s:%s" address port)]
    (.connect socket sock-addr)
    (.send socket message)
    (let [response (String. (.recv socket))]
      (.close socket)
      response)))

(defn check-responses
  [responses]
  responses)

(def task-defs
  {:record-audio
   {:call "record-audio"
    :params [44800 16 2 "recordings"]
    :repeat false}
   
   :spectrogram
   {:call "spectrogram"
    :params [256 0]
    :depends "record-audio"}})

(defn encode-task
  [task-name tasks & [params]]
  (let [task-def ((keyword task-name) tasks)]
    (-> (if (empty? params)
          (merge task-def {:name task-name})
          (merge task-def {:name task-name :params params}))
        (pr-str))))

(defn run-task
  [task-name & args]
  (let [message (encode-task task-name task-defs (vec args))]
    (-> (map (fn [node-def]
               (send-message (:address node-def) task-listen-port message))
             (:nodes node-tree))
        (check-responses))))








(defn -main
  [& args]
  (let [local-address (host-address "localhost")
        publish-socket (publisher local-address task-publish-port)
        task-listener (future (listener local-address task-listen-port (partial task-handler publish-socket)))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (.close publish-socket))))
    (println "Started qut-wsn listener...")
    @task-listener))
