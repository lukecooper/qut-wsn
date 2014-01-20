(ns pewson.control
  (:gen-class)
  (:import [org.jeromq ZMQ])
  (:require [clj-ssh.ssh :as ssh])
  (:require [me.raynes.conch :refer [programs with-programs let-programs]]))

(defn mdns-hostname
  [hostname]
  (str hostname ".local"))

(defn avahi-resolve
  [hostname]
  (:out (clojure.java.shell/sh "avahi-resolve-host-name" "-4" hostname)))

(defn host-lookup
  [hostname]
  (last ((comp #(clojure.string/split % #"\s+") avahi-resolve mdns-hostname) hostname)))

(defn push-files
  [path dest user hostname]
  (let [address (host-lookup hostname)
        command (str "rsync -avz " path " " user "@" address ":" dest)
        result (apply clojure.java.shell/sh (clojure.string/split command #"\s+"))]
    (printf (:out result))))

(defn get-files
  [path dest user hostname]
  (let [address (host-lookup hostname)
        command (str "rsync -avz " user "@" address ":" path " " dest)
        result (apply clojure.java.shell/sh (clojure.string/split command #"\s+"))]
    (printf (:out result))))

; local host
(defn generate-ssh-key
  [keyfile passphrase]
  (clojure.java.shell/sh "rm" keyfile (str keyfile ".pub"))
  (clojure.java.shell/sh "ssh-keygen" "-N" passphrase "-f" keyfile))

; local host
(defn copy-ssh-key
  [keyfile user host password]
  (let [command (str "sshpass -p " password " ssh-copy-id -i " keyfile " " user "@" (host-lookup host))]
    (apply clojure.java.shell/sh (clojure.string/split command #"\s+"))))

; remote host
(defn create-user
  [user host password sudo-password]
  (let [agent (ssh/ssh-agent {})
        session (ssh/session agent (host-lookup host) {:strict-host-key-checking :no})]
    (ssh/with-connection session
      (ssh/ssh session {:cmd (str "echo " sudo-password " | sudo -S sh -c 'useradd -m " user " && echo " user ":" password " | chpasswd'")}))))

; remote host
(defn passwordless-sudoer
  [user host sudo-password]
  (let [agent (ssh/ssh-agent {})
        session (ssh/session agent (host-lookup host) {:strict-host-key-checking :no})]
    (ssh/with-connection session
      (push-files "bin/sudoers.sh" "bin/" user host)
      (ssh/ssh session {:cmd (str "echo " sudo-password " | sudo -S sh -c 'bin/sudoers.sh " user " " sudo-password "'")}))))

