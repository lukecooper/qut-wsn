(ns qut-wsn.core
  (:gen-class)
  (:use [qut-wsn.control])
  (:use [qut-wsn.config])
  (:use [qut-wsn.task])        
  (:use [clojure.java.io :only [file]])
  (:use [clojure.java.shell :only [sh]])
  (:use [clojure.string :only [trim split join]])
  (:import [org.jeromq ZMQ])
  (:import [org.apache.commons.io FileUtils])
  (:require [taoensso.timbre :as timbre]))

;; include logging
(timbre/refer-timbre)
(timbre/set-config! [:appenders :spit :enabled?] true)
(timbre/set-config! [:shared-appender-config :spit-filename] "log/wsn.log")

;; default zeromq context for this app
(def ctx (ZMQ/context 1))

;; listen and publish port numbers
(def ^:const listen-port  47687)
(def ^:const publish-port 47688)

;; publish socket for this host
(defonce publish-socket (ref nil))

(def node-tree (load-config))

(def tasks
  [{:name "record"
    :repeat true
    :steps [{:call "record-audio"
             :params [44800 16 1]}
            {:call "move-file"
             :params ["recordings"]}]}
   
   {:name "spectrogram"
    :input "record"
    :repeat true
    :steps [{:call "spectrogram"
             :params [1024 0]}
            {:call "move-file"
             :params ["spectrograms"]}]}

   {:name "aci"
    :input "spectrogram"
    :repeat true
    :steps [{:call "aci"}
            {:call "move-file"
             :params ["aci"]}]}

   {:name "render-spectrogram"
    :input "spectrogram"
    :repeat true
    :steps [{:call "render-spectrogram"}]}

   {:name "render-aci"
    :input "aci"
    :repeat true
    :steps [{:call "copy-file"
             :params ["aci-render"]}
            {:call "render-aci"
             :params ["aci.png"]}]}])

(def queries
  [{:name "aci"
    :sensor ["record" "spectrogram" "render-spectrogram" "aci"]
    :collector ["render-aci"]}])

;;
;; RECIPIENT
;;

(defn append-filter
  [filter message]
  (join (list filter ":" message)))

(defn remove-filter
  [message]
  (second (split message #":" 2)))

(defn publish
  [socket source message]
  (dosync
   (info "Publishing result from" source message)
   (.send socket (append-filter source message))))

(defn wait-for
  [task-name]
  (if-not (nil? task-name)
    (let [socket (.socket ctx ZMQ/SUB)
          filter (str task-name)]
      (.connect socket (format "tcp://%s:%s" (host-address "localhost") publish-port))
      (.subscribe socket filter)
      (info "Waiting for" filter)
      (let [message (String. (.recv socket))]
        (info "Message received" message)
        (.close socket)
        (remove-filter message)))))

(defn call-step
  ([step]
     (call-step nil step))
  ([input step]
     (info "Calling step" step "with input" input)
     (let [partial-step (partial (resolve (symbol "qut-wsn.task" (step :call))))]              
       (apply partial-step (concat (filter (comp not nil?) ((comp flatten vector) input))
                                   (step :params))))))

(defn find-by-name
  [name coll]
  (first (filter #(= (:name %) name) coll)))

(defn run-steps
  [input steps]
  (if (empty? (rest steps))
    (call-step input (first steps))
    (reduce call-step input steps)))

(defn sensor-task
  [task-name publish]
  (let [task (find-by-name task-name tasks)]
    (loop [input (wait-for (task :input))]
      (info "Executing task" task "with input" input)
      (publish (task :name) (run-steps input (task :steps)))
      (if (task :repeat)
        (recur (wait-for (task :input)))))))

(declare send-message)

;; dispatch run-query on the role of the current host
(defmulti run-query (fn [query publish] (lookup-role (hostname) node-tree)))

(defmethod run-query "sensor"
  [query publish]
  ;; forward query to child nodes
  (doall (pmap (fn [node] (send-message (:address node) listen-port (pr-str query)))
               (lookup-nodes (hostname) node-tree)))
  ;; run sensor tasks
  (doall (pmap #(sensor-task % publish) (query :sensor))))

(defmethod run-query "collector"
  [query publish]
  ;; forward query to child nodes
  (doall (pmap (fn [node] (send-message (:address node) listen-port (pr-str query)))
               (lookup-nodes (hostname) node-tree)))
  ;; run collector tasks
  ;; (doall (pmap #(collector-task % publish) (query :sensor)))
  )

(declare decode-message)

(defn listen-for-query
  [address port publish-socket]
  (let [listen-socket (.socket ctx ZMQ/REP)]    
    (.bind listen-socket (format "tcp://%s:%s" address port))
    (info "Listening for queries")
    (loop [query (read-string (String. (.recv listen-socket)))]
      (info "Received query" (str query))            
      (future (run-query query (partial publish publish-socket)))
      (.send listen-socket (String. "ok"))      
      (recur (decode-message (String. (.recv listen-socket)))))))

(defn listen-for-result
  [host port publish-socket]
  (let [listen-socket (.socket ctx ZMQ/SUB)]    
    (.connect listen-socket (format "tcp://%s:%s" (host-address host) publish-port))
    (.subscribe listen-socket "")
    (info "Listening to" host "for results")
    (loop [result (String. (.recv listen-socket))]
      (info "Result from" host result)
      (let [[filter message] (split result #":" 2)]
        (publish publish-socket filter (join [host ":" message])))
      (recur (String. (.recv listen-socket))))))

(defn bind-publish-socket
  [address port]
  (when (nil? @publish-socket)
    (info "publish-socket nil")
    (dosync
     (ref-set publish-socket (.socket ctx ZMQ/PUB))
     (.bind @publish-socket (format "tcp://%s:%s" address port)))))

(defn -main
  [& args]
  (info "Starting")
  (let [local-address (host-address "localhost")]
    (bind-publish-socket local-address publish-port)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (dosync (.close @publish-socket)))))    
    (doall (pmap (fn [node]                   
                   (future (listen-for-result (node :name) publish-port @publish-socket)))
                 (lookup-nodes (hostname) node-tree)))    
    (listen-for-query local-address listen-port @publish-socket)))

;;
;; SENDER
;;

(defn check-responses
  [responses]
  responses)

(defn send-message
  "Synchronously sends a string message to the given address and returns the response."
  [address port message]
  (info "Send message")
  (let [socket (.socket ctx ZMQ/REQ)
        sock-addr (format "tcp://%s:%s" address port)]
    (info "Connecting to" address ":" port)
    (.connect socket sock-addr)
    (info "Sending" message)
    (.send socket message)
    (let [response (String. (.recv socket))]
      (info "Response" response)
      (.close socket)
      response)))

(defn map-query
  [query-name]  
  (bind-publish-socket (host-address "localhost") publish-port)
  (let [query (find-by-name query-name queries)
        listeners (doall (pmap (fn [node] (future (listen-for-result (node :name) publish-port @publish-socket)))
                               (lookup-nodes (hostname) node-tree)))]
    (when-not (nil? query)
      (run-query query @publish-socket))
    listeners))

(defn cancel-listeners
  [listeners]
  (doall  (pmap future-cancel listeners)))


