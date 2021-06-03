(ns wyr2.core
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [selmer.parser :as dj]
            [selmer.util]
            [ghostwheel.core :as g
             :refer [>defn >defn- >fdef => | << <- ?]]
            [clojure.string :as str]
            [expound.alpha :refer [expound]]
            [clojure.spec.gen.alpha :as gen]))

(selmer.util/turn-off-escaping!)

(def s-not-empty-string
  (s/and string?
         #(not= % "")))

(s/def ::section-field (s/tuple string? string?))
(s/def ::section-fields (s/coll-of ::section-field))

(s/def ::ip-prefix s-not-empty-string)

(s/def ::name keyword?)
(s/def ::net-id (s/int-in 1 255))
(s/def ::client-id (s/int-in 100 201))
(s/def ::iface-name s-not-empty-string)
(s/def ::local-name keyword?)
(s/def ::nat-interfaces (s/coll-of ::iface-name))
(s/def ::pubkey string?)
(s/def ::allowed-ips (s/coll-of s-not-empty-string))
(s/def ::public-ip s-not-empty-string)
(s/def ::endpoint s-not-empty-string)
(s/def ::listen-port int?)
(s/def ::connect-to (s/coll-of keyword? :min-count 1))
(s/def ::connect-to keyword?)
(s/def ::section-title s-not-empty-string)
(s/def ::section-comment s-not-empty-string)

(defn interface-gen
  [server-names]
  (gen/hash-map :connect-to (gen/elements server-names)))

(s/def ::interface (s/keys :req-un [::connect-to]))
(s/def ::interfaces (s/map-of string? ::interface :min-count 1))

(defn client-gen
  [server-names]
  (gen/hash-map :client-id (s/gen ::client-id)
                :pubkey (s/gen ::pubkey)
                :interfaces (gen/map (s/gen ::iface-name)
                                     (interface-gen server-names)
                                     {:min-elements 1})))

(s/def ::client (s/keys :req-un [::client-id ::pubkey ::interfaces]))
(s/def ::clients (s/map-of keyword? ::client :min-count 1))

(s/def ::server (s/keys :req-un [::public-ip ::pubkey ::net-id ::iface-name]))
(s/def ::servers (s/map-of keyword? ::server :min-count 1))

(s/def ::section (s/keys :req-un [::section-title ::section-comment ::section-fields]))
(s/def ::sections (s/coll-of ::section))

(defn netmap-gen
  []
  (gen/bind
   (s/gen ::servers)
   (fn [servers]
     (gen/hash-map :ip-prefix (s/gen ::ip-prefix)
                   :servers (gen/return servers)
                   :clients (gen/map
                             (gen/keyword)
                             (client-gen (keys servers))
                             {:min-elements 1})))))

(s/def ::netmap
  (s/keys :req-un [::ip-prefix ::servers ::clients]))

;; (defn map-k
;;   [f m]
;;   (into {} (for [[k v] m] [(f k) v])))

;; (defn map-v
;;   [f m]
;;   (into {} (for [[k v] m] [k (f v)])))

;; (defn filter-v
;;   [f m]
;;   (into {} (filter #(f (val %)) m)))

(>defn assoc-key
       [new-key m]
       [any? (s/map-of any? (s/map-of any? any?))
        => (s/coll-of any?)]
       (for [[key map-val] m]
         (assoc map-val new-key key)))

(>defn deindent
       [in]
       [string? => string?]
       (->> in
            str/split-lines
            (map str/trim)
            (str/join "\n")
            (#(str % "\n"))))

(defn netmap-validate-connections
  [nmap]
  (let [connection-paths (for [[client-name client] (:clients nmap)
                               [iface-name iface] (:interfaces client)]
                           {:client-name client-name
                            :client-iface iface-name
                            :server-name (:connect-to iface)})
        invalid-connections (filter #(not (get-in nmap [:servers (:server-name %)]))
                                    connection-paths)]
    (if (seq invalid-connections)
      (doseq [{:keys [client-name client-iface server-name]} invalid-connections]
        (println client-name client-iface server-name)
        (printf "Server not found: %s in [:clients %s :interfaces %s :connect-to]"
                server-name client-name client-iface))
      nmap)))

(defn netmap-validate-spec
  [nmap]
  (if-not (s/valid? ::netmap nmap)
    (do (println "Netmap is not valid:")
        (expound ::netmap nmap))
    nmap))


(defn validate-netmap
  [nmap]
  (some-> nmap
          netmap-validate-spec
          netmap-validate-connections))

(defn load-config-file
  [path]
  (->> path
       slurp
       edn/read-string
       validate-netmap))
#_(load-config-file "var/netmap.edn")

(>defn render-kv
       [fields]
       [(s/coll-of ::section-field)
        => string?]
       (str/join "\n" (map #(str/join " = " %) fields)))

(>defn render-section
       ([{:keys [section-title section-comment section-fields]}]
        [::section => string?]
        (render-section section-title section-comment section-fields))
       ([heading description fields]
        [string? string? ::section-fields
         => string?]
        (str/join "\n"
                  [(format "[%s]" heading)
                   (format "# %s" description)
                   (render-kv fields)
                   "\n"])))

(>defn render-sections
       [sections]
       [::sections => string?]
       (->> sections
            (map render-section)
            (str/join "\n")))

;; (>defn nat-rule
;;        [from to]
;;        [string? string? => ::kv-col]
;;        (let [args {:from from :to to}]
;;          [["PostUp"
;;            (dj/render "iptables -A FORWARD -i {{from}} -j ACCEPT; iptables -A FORWARD -o {{from}} -j ACCEPT; iptables -t nat -A POSTROUTING -o {{to}} -j MASQUERADE"
;;                       args)]
;;           ["PostDown"
;;            (dj/render "iptables -D FORWARD -i {{from}} -j ACCEPT; iptables -D FORWARD -o {{from}} -j ACCEPT; iptables -t nat -D POSTROUTING -o {{to}} -j MASQUERADE"
;;                       args)]]))
;; #_(nat-rule "wg0" "eth0")

(defn client-config-args
  []
  (-> (netmap-gen)
      (gen/bind (fn [nmap] (gen/tuple (gen/elements (keys (:clients nmap)))
                                      (gen/return nmap))))
      (gen/bind (fn [[client-name nmap]] (gen/tuple
                                          (gen/return client-name)
                                          (gen/elements (keys (get-in nmap [:clients client-name :interfaces])))
                                          (gen/return nmap))))))


(>defn client-config
       [client-name iface-name config]
       [keyword? string? ::netmap
        | #(let [server (get-in config [:clients client-name :interfaces iface-name :connect-to])]
             (get-in config [:servers server]))
        << client-config-args
        => string?]
       (let [ip-prefix (:ip-prefix config)

             client-config (get-in config [:clients client-name])
             client-id (:client-id client-config)
       ;;       persistent-keepalive (:persistent-keepalive client-config)

             iface-config (get-in client-config [:interfaces iface-name])
             server-name (:connect-to iface-config)

             server-config (get-in config [:servers server-name])
             net-id (:net-id server-config)
             server-pubkey (:pubkey server-config)
             server-ip (:public-ip server-config)

             output
             [{:section-title "Interface"
               :section-comment (format "Client: %s %s" client-name iface-name)
               :section-fields
               [["Address" (format "%s.%s.%s/24" ip-prefix net-id client-id)]
                ["PrivateKey" "__PRIVKEY__"]]}
              {:section-title "Peer"
               :section-comment (format "%s server" server-name)
               :section-fields
               [["PublicKey" server-pubkey]
                ["Endpoint" (str server-ip ":51820")]
                ["AllowedIps" (format "%s.%s.0/24" ip-prefix net-id)]
                ["PersistentKeepalive" "25"]]}]]

         (render-sections output)))
#_(->> "var/netmap.edn"
       load-config-file
       (client-config :ho1 "wg0")
       (spit "var/ho1-wg0.conf"))


(defn server-config-args
  []
  (-> (netmap-gen)
      (gen/bind (fn [nmap] (gen/tuple (gen/elements (keys (:servers nmap)))
                                      (gen/return nmap))))))

(>defn client-connects-to
       [server-name client-config]
       [keyword? ::client
        => boolean?]
       (->> client-config
            :interfaces
            (some #(-> % val :connect-to (= server-name)))
            boolean))

(>defn server-config
       [server-name config]
       [keyword? ::netmap
        << server-config-args
        => string?]
       (let [ip-prefix (:ip-prefix config)

             server-config (get-in config [:servers server-name])
             iface-name (:iface-name server-config)
             net-id (:net-id server-config)
             server-pubkey (:pubkey server-config)
             server-ip (:public-ip server-config)

             interface [{:section-title "Interface"
                         :section-comment (format "name = %s, iface = %s, role = server" server-name iface-name)
                         :section-fields
                         [["Address" (format "%s.%s.1/24" ip-prefix net-id)]
                          ["PrivateKey" "__PRIVKEY__"]]}]

             clients (->> (:clients config)
                          (assoc-key :client-name)
                          (filter #(client-connects-to server-name %))
                          (map (fn [{:keys [pubkey client-id client-name]}]
                                 {:section-title "Peer"
                                  :section-comment (str client-name)
                                  :section-fields
                                  [["PublicKey" pubkey]
                                   ["AllowedIps"
                                    (format "%s.%s.%s/32" ip-prefix net-id client-id)]]})))

             output (concat interface clients)]

         (render-sections output)))
#_(->> "var/netmap.edn"
       load-config-file
       (server-config :co2)
       (spit "var/co2-wg0.conf"))

(>defn linux-update-command
       [iface-name config-str]
       [string? string?
        => string?]
       (dj/render (slurp "resources/update-tpl.sh")
                  {:ifname iface-name
                   :config-str config-str}))

#_(->> "var/netmap.edn"
       load-config-file
       (client-config :ho1 "wg0")
       (linux-update-command "wg0")
       (spit "var/ho1-wg0.sh"))


(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))

(g/check)
