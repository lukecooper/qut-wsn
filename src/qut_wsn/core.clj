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
(timbre/set-config! [:fmt-output-fn] 
   (fn [{:keys [level throwable message timestamp hostname ns]}
       ;; Any extra appender-specific opts:
       & [{:keys [nofonts?] :as appender-fmt-output-opts}]]
     ;; <LEVEL> [<ns>] - <message> <throwable>
     (format "%s [%s] - %s%s"
       (-> level name clojure.string/upper-case) ns (or message "")
       (or (timbre/stacktrace throwable "\n" (when nofonts? {})) ""))))

;; default zeromq context for this app
(def ctx (ZMQ/context 1))

;; listen and publish port numbers
(def ^:const listen-port  47687)
(def ^:const publish-port 47688)

;; publish socket for this host
(defonce publish-socket (ref nil))

(def tasks
  [{:name "record"
    :repeat true
    :steps [{:call "record-audio"
             :params [44800 16 1]}
            {:call "move-file"
             :params ["data/recordings"]}]}
   
   {:name "spectrogram"
    :input "record"
    :repeat true
    :steps [{:call "calculate-spectrogram"
             :params [1024 0]}
            {:call "move-file"
             :params ["data/spectrograms"]}]}

   {:name "aci"
    :input "spectrogram"
    :repeat true
    :steps [{:call "calculate-aci"}
            {:call "move-file"
             :params ["data/aci"]}]}

   {:name "render-spectrogram"
    :input "spectrogram"
    :repeat true
    :steps [{:call "render-array"}]}

   {:name "get-aci"
    :input "aci"
    :repeat true
    :steps [{:call "copy-remote-file"
             :params ["data/aci"]}]}

   {:name "collate-aci"
    :input "get-aci"
    :repeat true
    :steps [{:call "append-aci"}]}

   {:name "render-aci"
    :input "collate-aci"
    :repeat true
    :steps [{:call "render-array"}]}

   {:name "get-spectrogram-images"
    :input "render-spectrogram"
    :repeat true
    :steps [{:call "copy-remote-file"
             :params ["data/spectrogram-images"]}]}

   {:name "update-network"
    :steps [{:call "update-network"}]}
   
   {:name "stop"
    :steps [{:call "stop"}]}])

(def queries
  [{:name "aci"
    :sensor ["record" "spectrogram" "render-spectrogram" "aci"]
    :collector ["get-aci" "get-spectrogram-images" "collate-aci" "render-aci"]}

   {:name "update-network"
    :sensor ["update-network"]
    :collector ["update-network"]}
   
   ;; stops all listeners
   {:name "stop"
    :sensor ["stop"]
    :collector ["stop"]}])

;;
;; RECIPIENT
;;

(defn append-filter
  [filter message]
  (join (list filter "|" message)))

(defn remove-filter
  [message]
  (second (split message #"\|" 2)))

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
      (info "subscribing to address" (host-value (hostname) :address))
      (.connect socket (format "tcp://%s:%s" (host-value (hostname) :address) publish-port))
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
      (publish
       (task :name) (run-steps input (task :steps)))
      (if (task :repeat)
        (recur (wait-for (task :input)))))))

(defn collector-task
  [task-name publish]
  (let [task (find-by-name task-name tasks)]
    (info "collector task running" task)
    (info "waiting for" (task :input))
    (loop [input (wait-for (task :input))]
      (let [[host message] (split input #"\|" 2)]
        (info "Executing task" task "with input" input "from host" host)
        (let [result (run-steps [host message] (task :steps))]
          (info "Task finished with result" result)
          (publish (task :name) result)
          (if (task :repeat)
            (recur (wait-for (task :input)))))))))

(declare send-message)

;; dispatch run-query on the role of the current host
(defmulti run-query (fn [query publish] (host-value (hostname) :role)))

(defmethod run-query "sensor"
  [query publish]
  ;; forward query to child nodes
  (doall (pmap (fn [node] (send-message (:address node) listen-port (pr-str query)))
               (host-value (hostname) :nodes)))
  ;; run sensor tasks
  (doall (pmap #(sensor-task % publish) (query :sensor))))

(defmethod run-query "collector"
  [query publish]
  (info "run-query collector")
  ;; forward query to child nodes
  (doall (pmap (fn [node] (send-message (:address node) listen-port (pr-str query)))
               (host-value (hostname) :nodes)))
  ;; run collector tasks
  (doall (pmap #(collector-task % publish) (query :collector))))

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
      (recur (read-string (String. (.recv listen-socket)))))))

(defn listen-for-result
  [hostname port publish-socket]
  (let [listen-socket (.socket ctx ZMQ/SUB)]
    (.connect listen-socket (format "tcp://%s:%s" (host-value hostname :address) publish-port))
    (.subscribe listen-socket "")
    (info "Listening to" hostname "for results")
    (loop [result (String. (.recv listen-socket))]
      (info "Result from" hostname result)
      (let [[filter message] (split result #"\|" 2)]
        (publish publish-socket filter (join [hostname "|" message]))
        (if-not (= filter "stop")        
          (recur (String. (.recv listen-socket))))))))

(defn bind-publish-socket
  [address port]
  (when (nil? @publish-socket)
    (info "creating publish socket on" address)
    (dosync
     (ref-set publish-socket (.socket ctx ZMQ/PUB))
     (.bind @publish-socket (format "tcp://%s:%s" address port)))))

(defn -main
  [& args]
  (info "Starting")  
  (let [local-address (host-value (hostname) :address)]
    (bind-publish-socket local-address publish-port)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (dosync (.close @publish-socket)))))    
    (doall (pmap (fn [node] (future (listen-for-result (node :name) publish-port @publish-socket)))
                 (host-value (hostname) :nodes)))    
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
  (let [socket (.socket ctx ZMQ/REQ)
        sock-addr (format "tcp://%s:%s" address port)]
    (.connect socket sock-addr)
    (info "Sending" message "to" address)
    (.send socket message)
    (let [response (String. (.recv socket))]
      (info "Response" response)
      (.close socket)
      response)))

(defn map-query
  [query-name]
  (bind-publish-socket (host-value (hostname) :address) publish-port)
  (doall (map (fn [node] (future (listen-for-result (node :name) publish-port @publish-socket)))
              (host-value (hostname) :nodes)))
  (let [query (find-by-name query-name queries)]
    (info "Running query")
    (when-not (nil? query)
      (run-query query (partial publish @publish-socket)))))

(defn stop-listeners
  []
  (run-query "stop" @publish-socket))

(defn map-network
  [network-filepath hosts-filepath]
  (let [network-config (load-config network-filepath hosts-filepath)]
    network-config))

(load-config "network.map" "hosts.list")
