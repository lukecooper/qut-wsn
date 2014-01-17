(ns pewson.control
  (:gen-class)
  (:import [org.jeromq ZMQ])
  (:require [clj-ssh.ssh :as ssh]))

(defn mdns-hostname
  [hostname]
  (str hostname ".local"))

(defn avahi-resolve
  [hostname]
  (:out (clojure.java.shell/sh "avahi-resolve-host-name" "-4" hostname)))

(defn host-lookup
  [hostname]
  (last ((comp #(clojure.string/split % #"\s+") avahi-resolve mdns-hostname) hostname)))

(defn test-copy
  [from to ip-addr]
  (let [agent (ssh/ssh-agent {})
        session (ssh/session agent ip-addr {:stric-host-key_check :no})]
    (ssh/with-connection session
      (ssh/scp-to session from to :recursive true))))
