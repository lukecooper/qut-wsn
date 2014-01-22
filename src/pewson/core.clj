(ns pewson.core
  (:gen-class)
  (:import [org.jeromq ZMQ])
  (:use [clojure.java.shell :only [sh]])
  (:use [pewson.control]))

(def ^:const task-listen-port 47687)
(def ^:const task-publish-port 47688)
(def ^:const status-listen-port 47689)

(defn ls
  []
  (:out (clojure.java.shell/sh "ls" "-lh")))

(defn grep
  [input]
  (:out (clojure.java.shell/sh "grep" "src" :in input)))

(def ls-taskdef
  {:name "ls"
   :exec "ls"
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
  (let [local-address (pewson.control/host-address "localhost")
        publish-socket (publisher local-address task-publish-port)
        task-listener (future (listener local-address task-listen-port (partial task-handler publish-socket)))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (.close publish))))
    @task-listener))

(defn test-listener
  []
  (let [local-address (pewson.control/host-address "localhost")
        publish-socket (publisher local-address task-publish-port)]
    (listener local-address task-listen-port (partial task-handler publish-socket))))

(defn test-tasker
  [hostname message]
  (let [socket (.socket ctx ZMQ/REQ)]
    (.connect socket (format "tcp://%s:%s" (pewson.control/host-address hostname) task-listen-port))
    (.send socket message)
    (let [response (String. (.recv socket))]
      (.close socket)
      response)))
