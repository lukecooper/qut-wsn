(ns pewson.control
  (:gen-class)
  (:import [org.jeromq ZMQ])
  (:require [clj-ssh.ssh :as ssh])
  (:require [pallet.compute])
  (:require [pallet.actions])
  (:require [pallet.api]))

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

(defn pallet-nodelist
  [hostname groupname]
  (let [hostaddress (host-lookup hostname)
        hostos :ubuntu]
    (pallet.compute/instantiate-provider
     "node-list"
                                        ;:node-list [[hostname, groupname, hostaddress, hostos]]
     :node-list [["debian", groupname, (host-lookup "debian"), :ubuntu]
                 ["raspberrypi", groupname, (host-lookup "raspberrypi"), :ubuntu]]
     )))

(defn pallet-ls
  [nodelist groupname]
  (pallet.api/lift
   (pallet.api/group-spec
    groupname
    :phases {:configure (pallet.api/plan-fn (pallet.actions/rsync-directory "~/uni/vre014/pewson" "~/"))})
   :compute nodelist))

(defn test-pallet
  [hostname filename]
  (let [groupname "pallet-group"
        nodelist (pallet-nodelist hostname groupname)]
    ;(print (:out (first (:result (first (:results (pallet-ls nodelist
                                        ;groupname)))))))
    (clojure.pprint/pprint (map #(:result %) (:results (pallet-ls nodelist groupname))))
))
