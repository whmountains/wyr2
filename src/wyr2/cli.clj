(ns wyr2.cli
  (:gen-class)
  (:require [wyr2.core :as core]))

(def netmap-path
  "var/netmap.edn")
(def output-path
  "var")

(defn load-config
  []
  (core/load-config-file netmap-path))

(defn gen-server
  [server-name]
  (let [config (load-config)
        iface-name (get-in config [:servers server-name :iface-name])
        config-str (core/server-config server-name config)
        update-cmd (core/linux-update-command iface-name config-str)]
    (spit (format "%s/%s-%s.conf" output-path (name server-name) iface-name)
          config-str)
    (spit (format "%s/%s-%s.sh" output-path (name server-name) iface-name)
          update-cmd)))

(defn gen-client
  ([client-name]
   (let [config (load-config)
         interfaces (get-in config [:clients client-name :interfaces])]
     (doseq [iface-name (keys interfaces)]
       (printf "Generating client config for %s %s" client-name iface-name)
       (gen-client client-name iface-name config))))
  ([client-name iface-name]
   (gen-client client-name iface-name (load-config)))
  ([client-name iface-name config]
   (let [config-str (core/client-config client-name iface-name config)
         update-cmd (core/linux-update-command iface-name config-str)]
     (spit (format "%s/%s-%s.conf" output-path (name client-name) iface-name)
           config-str)
     (spit (format "%s/%s-%s.sh" output-path (name client-name) iface-name)
           update-cmd))))