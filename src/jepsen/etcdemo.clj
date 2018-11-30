(ns jepsen.etcdemo
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure.string :as str]
            [knossos [model :as model]]
            [verschlimmbesserung.core :as v]
            [jepsen [cli :as cli]
                    [client :as client]
                    [control :as c]
                    [checker :as checker]
                    [db :as db]
                    [independent :as independent]
                    [generator :as gen]
                    [nemesis :as nemesis]
                    [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.checker.timeline :as timeline]
            [jepsen.os.debian :as debian]
            [slingshot.slingshot :refer [try+]]))

(def dir "/opt/etcd")
(def binary "etcd")
(def logfile (str dir "/etcd.log"))
(def pidfile (str dir "/etcd.pid"))

(defn node-url
  [node port]
  (str "http://" (name node) ":" port))

(defn peer-url
  [node]
  (node-url node 2380))

(defn client-url
  [node]
  (node-url node 2379))

(defn initial-cluster
  [test]
  (->> (:nodes test)
       (map (fn [node]
              (str node "=" (peer-url node))))
       (str/join ",")))

(defn db
  [version]
  (reify db/DB
    (setup! [_ test node]
      (c/su
      (info "Setting up db")
      (let [url (str "https://storage.googleapis.com/etcd/" version
                     "/etcd-" version "-linux-amd64.tar.gz")]
        (cu/install-archive! url dir)
        (cu/start-daemon! 
          {:logfile logfile
           :pidfile pidfile
           :chdir dir}
          binary
          :--log-output         :stderr
          :--name               node
          :--listen-peer-urls   (peer-url node)
          :--listen-client-urls (client-url node)
          :--advertise-client-urls (client-url node)
          :--initial-cluster-state :new
          :--initial-advertise-peer-urls (peer-url node)
          :--initial-cluster    (initial-cluster test))
        (Thread/sleep 10000))))

    (teardown! [_ test node]
      (info "Tearing down db")
      (cu/stop-daemon! binary pidfile)
      (c/su (c/exec :rm :-rf dir)))

   db/LogFiles
   (log-files [_ test node]
     [logfile])))

(defn r [test process]
{:type :invoke, :f :read, :value nil})

(defn w [test process]
{:type :invoke, :f :write, :value (rand-int 5)})

(defn cas [test process]
{:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn parse-long
  [x]
  (when x
    (Long/parseLong x)))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn (v/connect (client-url node)
                                 {:timeout 5000})))
  (setup! [this test])

  (invoke! [this test op]
    (let [[k v] (:value op)]
      (try+
        (case (:f op)
          :read (assoc op
                       :type :ok
                       :value (independent/tuple k (parse-long (v/get conn k {:quorum? true}))))
          :write (do (v/reset! conn k v)
                     (assoc op :type :ok))
          :cas   (let [[v v'] v] 
                   (assoc op :type (if (v/cas! conn k v v')
                                     :ok
                                     :fail))))
        (catch java.net.SocketTimeoutException ex
          (assoc op :type (if (= :read (:f op)) :fail :info), :error :timeout))
        (catch java.net.ConnectException ex
          (assoc op :type :fail, :error :connect))
        (catch [:errorCode 100] ex
          (assoc op :type :fail, :error :not-found)))))

    (teardown! [this test])

    (close! [this test]))

  (defn etcd-test
    "Take cli options and constructs a test map"
    [opts]
    (merge tests/noop-test
           opts
           {
            :name "etcd"
            :os debian/os
            :db (db "v3.1.5")
            :client (Client. nil)
            :nemesis (nemesis/partition-random-halves)
            :generator (->> (independent/concurrent-generator
                              10
                            (range)
                            (fn [key]
                              (->> (gen/mix [r w cas])
                                   (gen/stagger 1/10)
                                   (gen/limit 100))))
                          (gen/nemesis (->> [(gen/sleep 5)
                                             {:type :info, :f :start, :value nil}
                                             (gen/sleep 5)
                                             {:type :info, :f :stop, :value nil}]
                                            cycle
                                            gen/seq))
                          (gen/time-limit (:time-limit opts)))
          :checker (checker/compose {:linear (independent/checker (checker/linearizable))
                                      :perf (checker/perf)
                                      :timeline (independent/checker (timeline/html))})
          :model (model/cas-register)
          }))

(defn -main
  "Runs cmd line args"
  [& args] ; Read cmdline args
  (cli/run! (merge (cli/single-test-cmd {:test-fn etcd-test})
                   (cli/serve-cmd))
            args))
