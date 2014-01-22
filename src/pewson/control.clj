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

(defn host-address
  [hostname]
  (let [lookup-name (if (= (clojure.string/lower-case hostname) "localhost")
                      (clojure.string/trim (:out (clojure.java.shell/sh "hostname")))
                      hostname)]
    (last ((comp #(clojure.string/split % #"\s+") avahi-resolve mdns-hostname) lookup-name))))


(defn put-path
  [local-path remote-path user remote-addr]
  (format "rsync -avz %s %s@%s:%s" local-path user remote-addr remote-path))

(defn get-path
  [remote-path local-path user remote-addr]
  (format "rsync -avz %s@%s:%s %s" user remote-addr remote-path local-path))

(defn copy-ssh-key
  [user password host-address keyfile]
  (format "sshpass -p %s ssh-copy-id -i %s %s@%s" password keyfile user host-address))

(defn generate-ssh-key
  [keyfile passphrase]
  (format "ssh-keygen -N %s -f %s" passphrase keyfile))

(defn create-user
  [user password sudo-password]
  (format "echo %s | sudo -S sh -c 'useradd -m %s && echo %s:%s | chpasswd'" sudo-password user user password))

(defn make-user-sudo
  [user sudo-password]
  ; requires 'bin/sudoers.sh'
  (format "echo %s | sudo -S sh -c 'bin/sudoers.sh %s %s'" sudo-password user sudo-password))

(defn local-exec
  [& commands]
  (doall (map #(:out (apply clojure.java.shell/sh (clojure.string/split % #"\s+"))) commands)))

(defn remote-exec
  [host-address & commands]
  (let [agent (ssh/ssh-agent {})
        session (ssh/session agent host-address {:strict-host-key-checking :no})]
    (ssh/with-connection session
      (doall (map #(:out (ssh/ssh session {:cmd %})) commands)))))

(comment
  compile
  create jar
  collate dependencies
  stop service
  rsync jar and dependencies
  start service
  )
