(ns pewson.core
  (:gen-class)
  (:import [org.jeromq ZMQ])
  (:require (cheshire [core :as c]))
  (:use [clojure.java.shell :only [sh]]))

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
