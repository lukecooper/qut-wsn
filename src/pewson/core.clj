(ns pewson.core
  (:gen-class)
  (:import [org.jeromq ZMQ])
  (:use [clojure.java.shell :only [sh]])
  (:use [pewson.control]))

(def ^:const +task-listener-port+ 47687)
(def ^:const +status-listener-port+ 46876)

(defn ls
  []
  (:out (clojure.java.shell/sh "ls" "-lh")))

(defn grep
  [input]
  (:out (clojure.java.shell/sh "grep" "src" :in input)))

(def ls-taskdef
  {:name 'ls
   :exec ls
   :repeat false})

(def grep-taskdef
  {:name 'grep
   :exec grep
   :depends 'ls})

(def nodes [{:name 'somename
             :role 'sensor
             :arch 'pc}
            {:name 'someothername
             :role 'sensor
             :arch 'pc}
            {:name 'somecontroller
             :role 'controller
             :nodes [{:name 'qtpi01
                      :role 'sensor
                      :arch 'pi}
                     {:name 'qtpi02
                      :role 'sensor
                      :arch 'pi}
                     {:name 'qtpi03
                      :role 'sensor
                      :arch 'pi}]}])


(declare configure-node)

(defn configure-nodes
  [nodelist]
  (map configure-node nodelist))

(defn configure-node
  [nodedef]
  (let [name (:name nodedef)]  
    (if (contains? nodedef :nodes)
      (configure-nodes (:nodes nodedef)))))

(def ctx (ZMQ/context 1))

(defn gen-pubsock
  []
  (let [socket (.socket ctx ZMQ/PUB)]
    (.bind socket "tcp://127.0.0.1:8888")
    socket))

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
  [task-defn pubsock]
  (loop []
    (let [result (exec-task task-defn)]
      (println result)
      (publish pubsock result (:name task-defn)))
    (if (:repeat task-defn)
      (recur))))

(defn task-handler
  [message respond]
  (printf message)
  (.send respond (format "task OK - %s" message)))

(defn status-handler
  [message respond]
  (printf message)
  (.send respond (format "status OK - %s" message)))

(defn listener
  [address port handler]
  (let [listen (.socket ctx ZMQ/REP)]
    (.bind listen (format "tcp://%s:%s" address port))
    (loop [message (String. (.recv listen))]
      (handler message listen)
      (recur (String. (.recv listen))))))

(defn test-listener
  []
  (let [local-address (pewson.control/host-address "localhost")]
    (listener local-address +task-listener-port+ task-handler)))

(defn send-task
  [hostname message]
  (let [socket (.socket ctx ZMQ/REQ)]
    (.connect socket (format "tcp://%s:%s" (pewson.control/host-address hostname) +task-listener-port+))
    (.send socket message)
    (let [response (String. (.recv socket))]
      (.close socket)
      response)))

(defn send-status
  [hostname message]
  (let [socket (.socket ctx ZMQ/REQ)]
    (.connect socket (format "tcp://%s:%s" (pewson.control/host-address hostname) +status-listener-port+))
    (.send socket message)
    (let [response (String. (.recv socket))]
      (.close socket)
      response)))

(defn -main
  [& args]
  (let [local-address (pewson.control/host-address "localhost")
        task-listener (future (listener local-address +task-listener-port+ task-handler))
        status-listener (future (listener local-address +status-listener-port+ status-handler))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (future-cancel task-listener)
                                 (future-cancel status-listener))))
    (println @task-listener)))
