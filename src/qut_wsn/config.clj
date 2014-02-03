(ns qut-wsn.config
  (:gen-class)
  (:use [qut-wsn.control])
  (:use [clojure.java.io :only [file]]))

;; the network config for this node and it's children
(defonce network-config (ref nil))

;; local persistence of network config
(def ^:const network-config-filepath "network.config")

;;
;; load the network config from separate network and hosts files
;;
(defn merge-config
  [network hosts]
  (let [node-info ((keyword (:name network)) hosts)
        merged-def (merge network node-info)]
    (if (contains? merged-def :nodes)
      (merge merged-def {:nodes (mapv #(merge-config % hosts) (:nodes merged-def))})
      merged-def)))

(defn read-config
  [network-filepath hosts-filepath]
  (let [network (read-string (slurp network-filepath))
        hosts (read-string (slurp hosts-filepath))]
    [network hosts]))

(defn load-config
  [network-filepath hosts-filepath]
  (let [[network hosts] (read-config network-filepath hosts-filepath)]
    (dosync (ref-set network-config (merge-config network hosts)))
    @network-config))

;;
;; load the network config from a single merged file
;;
(defn load-network-config
  []
  (if (.exists (file network-config-filepath))
    (dosync (ref-set network-config (read-string (slurp network-config-filepath))))))

(defn save-network-config
  ([]
     (spit network-config-filepath (pr-str @network-config)))
  ([new-config]
     (dosync (ref-set network-config new-config))
     (save-network-config)))


;;
;; host lookup functions
;;

(defn find-host
  [hostname host-config]
  (if (vector? host-config)
    (if (nil? host-config)
      nil
      (first (filter (comp not nil?) (map #(find-host hostname %) host-config))))
    (if (= (host-config :name) hostname)
      host-config
      (find-host hostname (host-config :nodes)))))

(defn host-value
  [hostname value-fn]
  (let [host (find-host hostname @network-config)]
    (if-not (nil? host)
      (value-fn host))))

