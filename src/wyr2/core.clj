(ns wyr2.core
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [selmer.parser :as dj]
            [ghostwheel.core :as g
             :refer [>defn >defn- >fdef => | <- ?]]
            [clojure.string :as str]
            [expound.alpha :refer [expound]]
            [clojure.spec.gen.alpha :as gen]))

(s/def ::ip-prefix string?)

(s/def ::name keyword?)

(s/def ::net-id int?)
(s/def ::client-id (s/int-in 100 200))
(s/def ::ifname string?)
(s/def ::nat-interfaces (s/coll-of ::ifname))
(s/def ::pubkey string?)
(s/def ::allowed-ips (s/coll-of string?))
(s/def ::public-ip string?)

(s/def ::client (s/keys :req-un [::client-id]))
(s/def ::clients (s/map-of keyword? ::client))

(s/def ::server (s/keys :req-un [::public-ip ::net-id ::ifname ::nat-interfaces]))
(s/def ::servers (s/map-of keyword? ::server))

(s/def ::netmap (s/keys :req-un [::ip-prefix ::servers ::clients]))

(>defn deindent
       [in]
       [string? => string?]
       (->> in
            str/split-lines
            (map str/trim)
            (str/join "\n")
            (#(str % "\n"))))

(defn maybe-explain
  [spec data]
  (if (s/valid? spec data)
    data
    (expound spec data)))


(defn load-config-file
  [path]
  (->> path
       slurp
       edn/read-string
       (maybe-explain ::netmap)))
#_(load-config-file "netmap.edn")

(>defn nat-rule
       [from to]
       [string? string? => string?]
       (dj/render
        (deindent "PostUp=   iptables -A FORWARD -i {{from}} -j ACCEPT; iptables -A FORWARD -o {{from}} -j ACCEPT; iptables -t nat -A POSTROUTING -o {{to}} -j MASQUERADE
                   PostDown= iptables -D FORWARD -i {{from}} -j ACCEPT; iptables -D FORWARD -o {{from}} -j ACCEPT; iptables -t nat -D POSTROUTING -o {{to}} -j MASQUERADE")
        {:from from
         :to to}))
#_(nat-rule "wg0" "eth0")

(>defn interface-part
       [config]
       [(s/keys :req-un [::name ::ip-prefix ::net-id ::nat-interfaces]
                :opt [::ifname ::client-id]) | #(if (seq (:nat-interfaces config)) (string? (:ifname config)) true)
        => string?]
       (let [{:keys [ifname nat-interfaces]} config
             nat-rules (->> nat-interfaces
                            (map #(nat-rule ifname %))
                            (apply str))
             tpl-vars (assoc config :nat-rules nat-rules)]
         (dj/render
          (deindent "# {{name}}
                     [Interface]
                     Address={{ip-prefix}}.{{net-id}}.{{client-id|default:1}}/24
                     ListenPort=51820
                     PrivateKey=__PRIVKEY__
                     {{nat-rules}}")
          tpl-vars)))

(>defn peer-part
       [config]
       [(s/keys :req-un [::pubkey ::allowed-ips ::name]) => string?]
       (dj/render
        (deindent "[Peer]
                   # {{name}}
                   PublicKey={{pubkey}}
                   AllowedIps={{allowed-ips|join:\", \"}}") config))
#_(peer-part {:pubkey "abcdef"
              :allowed-ips "192.168.103.3/32"
              :name "Test Peer"})

(>defn client-config
       [client server config]
       [keyword? keyword? ::netmap
        | #(and (get-in config [:servers server])
                (get-in config [:clients client]))
        <- #(vector)
        => string?]
       (let [ip-prefix (:ip-prefix config)
             server-config (get-in config [:servers server])
             interface (interface-part
                        (merge {:name client
                                :ip-prefix ip-prefix
                                :net-id (:net-id server-config)}
                               (get-in config [:clients client])))
             peers (peer-part {:pubkey (:pubkey server-config)
                               :allowed-ips [(str/join "." [ip-prefix
                                                            (:net-id server-config)
                                                            "0/24"])]
                               :name server})]
         (str interface
              "\n"
              peers)))
#_(->> "netmap.edn"
       load-config-file
       (client-config :calebs-laptop :co1)
       (spit "client-config.txt"))


(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))

(g/check)
