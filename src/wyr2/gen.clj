(ns wyr2.gen
  (:require
   [clojure.edn :as edn]
   [clojure.spec.alpha :as s]
   [selmer.parser :as dj]
   [selmer.util]
   [clojure.string :as str]
   [expound.alpha :refer [expound]]
   [clojure.spec.gen.alpha :as gen]))

(selmer.util/turn-off-escaping!)

(def s-not-empty-string
  (s/and string?
         #(not= % "")))

(s/def ::allowed-ips (s/coll-of s-not-empty-string))
(s/def ::client-id (s/int-in 100 201))
(s/def ::connect-to (s/coll-of keyword? :min-count 1))
(s/def ::connect-to keyword?)
(s/def ::endpoint s-not-empty-string)
(s/def ::iface-name s-not-empty-string)
(s/def ::ip-prefix s-not-empty-string)
(s/def ::listen-port int?)
(s/def ::local-name keyword?)
(s/def ::name keyword?)
(s/def ::nat-interface (s/keys :req-un [::iface-name ::subnet]))
(s/def ::nat-interfaces (s/coll-of ::nat-interface))
(s/def ::net-id (s/int-in 1 255))
(s/def ::pubkey s-not-empty-string)
(s/def ::public-ip s-not-empty-string)
(s/def ::section-comment s-not-empty-string)
(s/def ::section-field (s/tuple string? string?))
(s/def ::section-fields (s/coll-of ::section-field))
(s/def ::section-title s-not-empty-string)
(s/def ::subnet s-not-empty-string)

(defn interface-gen
  [server-names]
  (gen/one-of [(gen/hash-map :connect-to (gen/elements server-names))
               (gen/hash-map :connect-to (gen/elements server-names)
                             :nat-interfaces (s/gen ::nat-interfaces))]))

(s/def ::interface (s/keys :req-un [::connect-to]
                           :opt-un [::nat-interfaces]))
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

(s/def ::server (s/keys :req-un [::public-ip ::pubkey ::net-id ::iface-name]
                        :req-opt [::nat-interfaces]))
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

(defn assoc-key
  [new-key m]
  #_[any? (s/map-of any? (s/map-of any? any?))
     => (s/coll-of any?)]
  (for [[key map-val] m]
    (assoc map-val new-key key)))

(defn deindent
  [in]
  #_[string? => string?]
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

(defn render-kv
  [fields]
  #_[(s/coll-of ::section-field)
     => string?]
  (str/join "\n" (map #(str/join " = " %) fields)))

(defn render-section
  ([{:keys [section-title section-comment section-fields]}]
   #_[::section => string?]
   (render-section section-title section-comment section-fields))
  ([heading description fields]
   #_[string? string? ::section-fields
      => string?]
   (str/join "\n"
             [(format "[%s]" heading)
              (format "# %s" description)
              (render-kv fields)
              "\n"])))

(defn render-sections
  [sections]
  #_[::sections => string?]
  (->> sections
       (map render-section)
       (str/join "\n")))

(defn nat-rule
  [from to]
  #_[string? string? => ::section-fields]
  (let [args {:from from :to to}]
    [["PostUp"
      (dj/render "iptables -A FORWARD -i {{from}} -j ACCEPT; iptables -A FORWARD -o {{from}} -j ACCEPT; iptables -t nat -A POSTROUTING -o {{to}} -j MASQUERADE"
                 args)]
     ["PostDown"
      (dj/render "iptables -D FORWARD -i {{from}} -j ACCEPT; iptables -D FORWARD -o {{from}} -j ACCEPT; iptables -t nat -D POSTROUTING -o {{to}} -j MASQUERADE"
                 args)]]))
#_(nat-rule "wg0" "eth0")

(defn client-connects-to
  [server-name client-config]
  #_[keyword? ::client
     => boolean?]
  (->> client-config
       :interfaces
       (some #(-> % val :connect-to (= server-name)))
       boolean))


(defn client->server-allowed-ips-args
  []
  (-> (netmap-gen)
      (gen/bind (fn [nmap] (gen/tuple
                            (s/gen ::ip-prefix)
                            (s/gen ::net-id)
                            (gen/elements (keys (:servers nmap)))
                            (gen/return nmap))))))

(defn client->server-allowed-ips
  [ip-prefix net-id server-name config]
  #_[::ip-prefix ::net-id keyword? ::netmap
     :gen client->server-allowed-ips-args
     :ret string?]
  (let [nat-interfaces (get-in config [:servers server-name :nat-interfaces])

        natural-subnet (format "%s.%s.0/24" ip-prefix net-id)
        server-subnets (->> nat-interfaces
                            (map :subnet))
        client-subnets (->> (:clients config)
                            (assoc-key :client-name)
                            (filter #(client-connects-to server-name %))
                            (mapcat :interfaces)
                            vals
                            (mapcat :nat-interfaces)
                            (map :subnet))

        all-subnets (concat [natural-subnet]
                            server-subnets
                            client-subnets)]
    (str/join ", " all-subnets)))

(defn client-config-args
  []
  (-> (netmap-gen)
      (gen/bind (fn [nmap] (gen/tuple (gen/elements (keys (:clients nmap)))
                                      (gen/return nmap))))
      (gen/bind (fn [[client-name nmap]] (gen/tuple
                                          (gen/return client-name)
                                          (gen/elements (keys (get-in nmap [:clients client-name :interfaces])))
                                          (gen/return nmap))))))

(defn client-config
  [client-name iface-name config]
  #_[keyword? string? ::netmap
     :st #(let [server (get-in config [:clients client-name :interfaces iface-name :connect-to])]
            (get-in config [:servers server]))
     :get client-config-args
     :ret string?]
  (let [ip-prefix (:ip-prefix config)

        client-config (get-in config [:clients client-name])
        client-id (:client-id client-config)

        iface-config (get-in client-config [:interfaces iface-name])
        server-name (:connect-to iface-config)
        local-nat-interfaces (:nat-interfaces iface-config)

        server-config (get-in config [:servers server-name])
        net-id (:net-id server-config)
        server-pubkey (:pubkey server-config)
        server-ip (:public-ip server-config)

        output
        [{:section-title "Interface"
          :section-comment (format "Client: %s %s" client-name iface-name)
          :section-fields
          (concat
           [["Address" (format "%s.%s.%s/24" ip-prefix net-id client-id)]
            ["PrivateKey" "__PRIVKEY__"]]
           (when local-nat-interfaces
             (mapcat nat-rule
                     (repeat iface-name)
                     (map :iface-name local-nat-interfaces))))}
         {:section-title "Peer"
          :section-comment (format "%s server" server-name)
          :section-fields
          [["PublicKey" server-pubkey]
           ["Endpoint" (str server-ip ":51820")]
           ["PersistentKeepalive" "25"]
           ["AllowedIps"
            (client->server-allowed-ips
             ip-prefix
             net-id
             server-name
             config)]]}]]

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

(defn server->client-allowed-ips
  [ip-prefix net-id client-id interfaces]
  #_[::ip-prefix ::net-id ::client-id ::interfaces => string?]
  (let [natural-subnet (format "%s.%s.%s/32" ip-prefix net-id client-id)
        extra-subnets (->> interfaces
                           vals
                           (mapcat :nat-interfaces)
                           (map :subnet))
        all-subnets (cons natural-subnet extra-subnets)]
    (str/join ", " all-subnets)))

(defn server-config
  [server-name config]
  #_[keyword? ::netmap
     :gen server-config-args
     :ret string?]
  (let [ip-prefix (:ip-prefix config)

        server-config (get-in config [:servers server-name])
        iface-name (:iface-name server-config)
        net-id (:net-id server-config)
        nat-interfaces (:nat-interfaces server-config)

        interface [{:section-title "Interface"
                    :section-comment (format "name = %s, iface = %s, role = server" server-name iface-name)
                    :section-fields
                    (concat
                     [["Address" (format "%s.%s.1/24" ip-prefix net-id)]
                      ["PrivateKey" "__PRIVKEY__"]
                      ["ListenPort" "51820"]]
                     (when nat-interfaces
                       (mapcat nat-rule (repeat iface-name) (map :iface-name nat-interfaces))))}]

        clients (->> (:clients config)
                     (assoc-key :client-name)
                     (filter #(client-connects-to server-name %))
                     (map (fn [{:keys [pubkey client-name client-id interfaces]}]
                            {:section-title "Peer"
                             :section-comment (str client-name)
                             :section-fields
                             [["PublicKey" pubkey]
                              ["AllowedIps"
                               (server->client-allowed-ips
                                ip-prefix
                                net-id
                                client-id
                                interfaces)]]})))

        output (concat interface clients)]

    (render-sections output)))
#_(->> "var/netmap.edn"
       load-config-file
       (server-config :co2)
       (spit "var/co2-wg0.conf"))

(defn linux-update-command
  [iface-name config-str]
  #_[string? string?
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
