(ns wyr2.cli
  (:require [wyr2.gen :as core]))

(def profiles
  {:tri
   {:netmap "var/tri/netmap.edn"
    :output "var/tri/out"}

   :whiting
   {:netmap "var/whiting/netmap.edn"
    :output "var/whiting/out"}})

(defn get-netmap-path
  [profile]
  (get-in profiles [profile :netmap]))

(defn get-output-path
  [profile]
  (get-in profiles [profile :output]))

(defn load-config
  [profile]
  (core/load-config-file (get-netmap-path profile)))

(defn gen-server
  [profile server-name]
  (let [config (load-config profile)
        iface-name (get-in config [:servers server-name :iface-name])
        config-str (core/server-config server-name config)
        update-cmd (core/linux-update-command iface-name config-str)
        output-path (get-output-path profile)]
    (spit (format "%s/%s-%s.conf" output-path (name server-name) iface-name)
          config-str)
    (spit (format "%s/%s-%s.sh" output-path (name server-name) iface-name)
          update-cmd)))

(defn gen-client
  ([profile client-name]
   (let [config (load-config profile)
         interfaces (get-in config [:clients client-name :interfaces])]
     (doseq [iface-name (keys interfaces)]
       (printf "Generating client config for %s %s\n" client-name iface-name)
       (gen-client profile client-name iface-name config))))
  ([profile client-name iface-name]
   (gen-client profile client-name iface-name (load-config profile)))
  ([profile client-name iface-name config]
   (let [config-str (core/client-config client-name iface-name config)
         update-cmd (core/linux-update-command iface-name config-str)
         output-path (get-output-path profile)]
     (println "saving config to" (format "%s/%s-%s.conf" output-path (name client-name) iface-name))
     (spit (format "%s/%s-%s.conf" output-path (name client-name) iface-name)
           config-str)
     (spit (format "%s/%s-%s.sh" output-path (name client-name) iface-name)
           update-cmd))))