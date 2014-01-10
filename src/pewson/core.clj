(ns pewson.core
  (:gen-class)
  (:import [org.jeromq ZMQ])
  (:require (cheshire [core :as c])))
 
(def ctx (ZMQ/context 1))
 
;
; The Request-Reply Pattern
;
; Server
(defn echo-server
  []
  (let [s (.socket ctx ZMQ/REP)]
    (.bind s "tcp://127.0.0.1:5554")
    (loop [msg (String. (.recv s))]
      (.send s (pr-str (load-string msg)))
      (recur (String. (.recv s))))))
 
; Client
(defn echo
  [msg]
  (let [s (.socket ctx ZMQ/REQ)]
    (.connect s "tcp://127.0.0.1:5554")
    (.send s msg)
    (println "Server replied:" (String. (.recv s)))
    (.close s)))
 
;
; The Publish-Subscribe Pattern
;
; Server
(defn market-data-publisher
  []
  (let [s (.socket ctx ZMQ/PUB)
        market-data-event (fn []
                            {:symbol (rand-nth ["CAT" "UTX"])
                             :size (rand-int 1000)
                             :price (format "%.2f" (rand 50.0))})]
    (.bind s "tcp://127.0.01:6666")
    (while :true
      (.send s (c/generate-string (market-data-event))))))
 
; Client
(defn get-market-data
  [num-events]
  (let [s (.socket ctx ZMQ/SUB)]
    (.subscribe s "")
    (.connect s "tcp://127.0.01:6666")
    (dotimes [_ num-events]
      (println (c/parse-string (String. (.recv s)))))
    (.close s)))
 
;
; The Pipeline Pattern
;
; Dispatcher
(defn dispatcher
  [jobs]
  (let [s (.socket ctx ZMQ/PUSH)]
    (.bind s "tcp://127.0.01:7777")
    (Thread/sleep 1000)   
    (dotimes [n jobs]
      (.send s (str n)))
    (.close s)))
 
; Worker
(defn worker
  []
  (let [rcv (.socket ctx ZMQ/PULL)
        snd (.socket ctx ZMQ/PUSH)
        id (str (gensym "w"))]
    (.connect rcv "tcp://127.0.01:7777")
    (.connect snd "tcp://127.0.01:8888")
    (while :true
      (let [job-id (String. (.recv rcv))
            proc-time (rand-int 100)]
        (Thread/sleep proc-time)
        (.send snd (c/generate-string {:worker-id id
                                       :job-id job-id
                                       :processing-time proc-time}))))))
 
; Collector
(defn collector
  []
  (let [s (.socket ctx ZMQ/PULL)]
    (.bind s "tcp://127.0.01:8888")
    (while :true
      (->> (.recv s)
           (String.)
           (c/parse-string)
           (println "Job completed:")))))

;(defn -main
;    "Comment"
;    [& args]
;    (echo-server))
