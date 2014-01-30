(ns qut-wsn.config
  (:gen-class)
  (:use [qut-wsn.control])
  (:use [clojure.java.io :only [file]]))

(def ^:const network-filepath "network.clj")
(def ^:const hosts-filepath "hosts.clj")

(defn merge-config
  [network hosts]
  (let [node-info ((keyword (:name network)) hosts)
        merged-def (merge network node-info)]
    (if (contains? merged-def :nodes)
      (merge merged-def {:nodes (mapv #(merge-config % hosts) (:nodes merged-def))})
      merged-def)))

(defn read-config
  []
  (let [network (read-string (slurp network-filepath))
        hosts (read-string (slurp hosts-filepath))]
    [network hosts]))

(defn load-config
  []
  (let [[network hosts] (read-config)]
    (merge-config network hosts)))

(declare find-host-in-list)
(defn find-host
  [hostname host-tree]
  (if (= (host-tree :name) hostname)
    host-tree
    (find-host-in-list hostname (host-tree :nodes))))

(defn find-host-in-list
  [hostname host-list]
  (if (nil? host-list)
    nil
    (first (filter (comp not nil?) (map #(find-host hostname %) host-list)))))

(defn lookup-role
  [hostname host-tree]
  (let [host (find-host hostname host-tree)]
    (if-not (nil? host)
      (host :role))))

(defn lookup-nodes
  [hostname host-tree]
  (let [host (find-host hostname host-tree)]
    (if-not (nil? host)
      (host :nodes))))
