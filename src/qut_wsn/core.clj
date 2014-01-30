(ns qut-wsn.core
  (:gen-class)
  (:use [qut-wsn.control])
  (:use [qut-wsn.config])
  (:use [qut-wsn.task])        
  (:use [clojure.java.io :only [file]])
  (:use [clojure.java.shell :only [sh]])
  (:use [clojure.string :only [trim split join]])
  (:import [org.jeromq ZMQ])
  (:import [org.apache.commons.io FileUtils]))

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
             :params [44800 16 2]}
            {:call "move-file"
             :params ["recordings"]}]}
   
   {:name "spectrogram"
    :input "record"
    :repeat true
    :steps [{:call "calculate-spectrogram"
             :params [256 0]}
            {:call "move-file"
             :params ["spectrograms"]}]}

   {:name "aci"
    :input "spectrogram"
    :repeat true
    :steps [{:call "calculate-aci"}
            {:call "move-file"
             :params ["aci"]}]}

   {:name "render-aci"
    :input "aci"
    :repeat true
    :steps [{:call "copy-file"
             :params ["aci-render"]}
            {:call "render-aci"
             :params ["aci.png"]}]}])

(def queries
  [{:name "aci"
    :sensor ["record" "spectrogram" "aci"]
    :collector ["render-aci"]}])

(defn append-filter
  [filter message]
  (join (list filter ":" message)))

(defn remove-filter
  [message]
  (second (split message #":" 2)))

(defn publish
  [socket source message]
  (.send socket (append-filter source message)))

(defn wait-for
  [task-name]
  (let [socket (.socket ctx ZMQ/SUB)
        filter (str task-name)]
    (.connect socket (format "tcp://%s:%s" (host-address "localhost") publish-port))
    (.subscribe socket filter)
    (let [message (String. (.recv socket))]
      (.close socket)
      (remove-filter message))))

(defn call-step
  ([step]
     (call-step nil step))
  ([input step]
     (let [partial-step (partial (resolve (symbol "qut-wsn.task" (step :call))))]
       (if (nil? input)
         (apply partial-step (step :params))
         (apply partial-step (cons input (step :params)))))))

(defn find-by-name
  [name coll]
  (first (filter #(= (:name %) name) coll)))

(defn wait-for2
  [task]
  nil)

(defn sensor-task
  [task-name publish]
  (let [task (find-by-name task-name tasks)
        steps (task :steps)]
    (loop [input (wait-for2 (task :input))]
      (publish (task :name)
               (if (empty? (rest steps))
                 (call-step input (first steps))
                 (reduce call-step input steps)))
      (if (task :repeat)
        (recur (wait-for2 (task :input)))))))


;;
;; RECIPIENT
;;

(defn run-query
  [query publish]
  (let [role (lookup-role (hostname) node-tree)
        tasks (query (keyword role))]
    (when (= role "sensor")
      (doall (map #(sensor-task % publish) tasks)))
    (when (= role "collector")
      ;; set up collector tasks
      (let [nodes (lookup-nodes (hostname) node-tree)
            message (pr-str query)]
        (map (fn [node] (send-message (:address node) listen-port message)) nodes)))))

(defn listen
  [address port publish-socket]
  (let [listen-socket (.socket ctx ZMQ/REP)]
    (.bind listen-socket (format "tcp://%s:%s" address port))
    (loop [query (read-string (String. (.recv listen-socket)))]
      (if-not (nil? query)
        (do
          (future (run-query query (partial publish publish-socket)))
          (.send listen-socket (String. "ok")))
        (.send listen-socket (String. "not found")))
      (recur (decode-message (String. (.recv listen-socket)))))))

(defn make-publish-socket
  [address port]
  (let [publish-socket (.socket ctx ZMQ/PUB)]
    (.bind publish-socket (format "tcp://%s:%s" address port))
    publish-socket))

(defn -main
  [& args]
  (let [local-address (host-address "localhost")
        publish-socket (make-publish-socket local-address publish-port)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (.close publish-socket))))
    (println "Started qut-wsn...")
    (listen local-address listen-port publish-socket)))

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
    (.send socket message)
    (let [response (String. (.recv socket))]
      (.close socket)
      response)))

(comment
  ;; left this here as an example of param passing
  (defn encode-message
    [query & [params]]
    (let [query-def (find-by-name query queries)]
      (comment
        (-> (if (empty? params)
              (merge query-def {:name task-name})
              (merge task-def {:name task-name :params params}))
            (pr-str)))
      (pr-str query-def))))

(defn run-query
  [query-name]
  (let [query (find-by-name query-name queries)]
    (when-not (nil? query)
      (run-query query nil))))
