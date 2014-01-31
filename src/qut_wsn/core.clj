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
       (if (nil? input)
         (apply partial-step (step :params))
         (apply partial-step (cons input (step :params)))))))

(defn find-by-name
  [name coll]
  (first (filter #(= (:name %) name) coll)))

(defn sensor-task
  [task-name publish]
  (let [task (find-by-name task-name tasks)
        steps (task :steps)]
    (loop [input (wait-for (task :input))]
      (info "Executing task" task "with input" input)
      (publish (task :name)
               (if (empty? (rest steps))
                 (call-step input (first steps))
                 (reduce call-step input steps)))
      (if (task :repeat)
        (recur (wait-for (task :input)))))))

(declare send-message)
(declare decode-message)

(defonce publish-socket (ref nil))

(defn bind-publish-socket
  [address port]
  (when (nil? @publish-socket)
    (info "publish-socket nil")
    (dosync
     (ref-set publish-socket (.socket ctx ZMQ/PUB))
     (.bind @publish-socket (format "tcp://%s:%s" address port)))))

(defn run-query
  ;; controller version
  ([query-name]
     (bind-publish-socket (host-address "localhost") publish-port)
     (let [query (find-by-name query-name queries)]
       (when-not (nil? query)
         (run-query query @publish-socket))))
  
  ;; node version
  ([query publish]
     (info "Run" query "with publish" (str publish))
     (let [role (lookup-role (hostname) node-tree)
           tasks (query (keyword role))]
       (when (= role "sensor")
         (info "Sensor running tasks" tasks)
         (doall (pmap #(sensor-task % publish) tasks)))
       (when (= role "collector")
         ;; set up collector tasks         
         (let [nodes (lookup-nodes (hostname) node-tree)
               message (pr-str query)]
           (info "Collector sending query to nodes" nodes "message" message)
           (doall (pmap (fn [node] (send-message (:address node) listen-port message)) nodes)))))))

(defn listen
  [address port publish-socket]
  (let [listen-socket (.socket ctx ZMQ/REP)]
    (info "Binding to" address port)
    (.bind listen-socket (format "tcp://%s:%s" address port))
    (loop [query (read-string (String. (.recv listen-socket)))]
      (info "Received query" (str query))
      (if-not (nil? query)
        (do
          (future (run-query query (partial publish publish-socket)))
          (.send listen-socket (String. "ok")))
        (.send listen-socket (String. "not found")))
      (recur (decode-message (String. (.recv listen-socket)))))))

(defn -main
  [& args]
  (let [local-address (host-address "localhost")]
    (bind-publish-socket local-address publish-port)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (dosync (.close @publish-socket)))))
    (info "Started qut-wsn...")
    (listen local-address listen-port @publish-socket)))

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
