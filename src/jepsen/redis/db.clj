(ns jepsen.redis.db
    (:require [clojure.tools.logging :refer :all]
              [clojure.string :as str]
              [jepsen.db :as db]
              [jepsen.control :as c]
              [jepsen.control.util :as cu]
              [jepsen.util :refer [meh]]
              [clojure.java.io :as io]))

(def redis-dir "/opt/redis")
(def redis-bin (str redis-dir "/src/redis-server"))
(def redis-cli (str redis-dir "/src/redis-cli"))
(def sentinel-bin (str redis-dir "/src/redis-sentinel"))
(def redis-config "/etc/redis/redis.conf")
(def sentinel-config "/etc/redis/sentinel.conf")
(def redis-pidfile "/var/run/redis.pid")
(def sentinel-pidfile "/var/run/sentinel.pid")
(def redis-logfile "/var/log/redis.log")
(def sentinel-logfile "/var/log/sentinel.log")

(defn install!
  "Install Redis from source"
  [version]
  (info "Installing Redis" version)
  (c/su
   (c/exec :apt-get :update)
   (c/exec :apt-get :install :-y :build-essential :tcl)
   (c/exec :mkdir :-p redis-dir)
   (c/cd redis-dir
         (c/exec :wget (str "http://download.redis.io/releases/redis-" version ".tar.gz"))
         (c/exec :tar :xzf (str "redis-" version ".tar.gz") :--strip-components 1)
         (c/exec :make))
   (c/exec :mkdir :-p "/etc/redis")
   (c/exec :mkdir :-p "/var/log")))

(defn redis-conf
  "Generate Redis configuration"
  [node nodes]
  (let [master? (= node (first nodes))]
    (str "# Redis configuration
port 6379
bind 0.0.0.0
timeout 0
tcp-keepalive 0
loglevel notice
logfile " redis-logfile "
databases 16
save 900 1
save 300 10
save 60 10000
stop-writes-on-bgsave-error yes
rdbcompression yes
rdbchecksum yes
dbfilename dump.rdb
dir /var/lib/redis/
slave-serve-stale-data yes
slave-read-only yes
slave-priority 100
maxmemory-policy noeviction
appendonly no
appendfsync everysec
no-appendfsync-on-rewrite no
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb
lua-time-limit 5000
slowlog-log-slower-than 10000
slowlog-max-len 128
hash-max-ziplist-entries 512
hash-max-ziplist-value 64
list-max-ziplist-entries 512
list-max-ziplist-value 64
set-max-intset-entries 512
zset-max-ziplist-entries 128
zset-max-ziplist-value 64
activerehashing yes
client-output-buffer-limit normal 0 0 0
client-output-buffer-limit slave 256mb 64mb 60
client-output-buffer-limit pubsub 32mb 8mb 60
hz 10

" (when-not master?
            (str "slaveof " (first nodes) " 6379\n")))))

(defn sentinel-conf
  "Generate Sentinel configuration"
  [node nodes]
  (str "# Sentinel configuration
port 26379
bind 0.0.0.0
sentinel monitor mymaster " (first nodes) " 6379 3
sentinel down-after-milliseconds mymaster 5000
sentinel parallel-syncs mymaster 1
sentinel failover-timeout mymaster 10000
logfile " sentinel-logfile "
"))

(defn setup-configs!
  "Set up Redis and Sentinel configuration files"
  [node nodes]
  (c/su
   (c/exec :mkdir :-p "/var/lib/redis")
   (c/exec :echo (redis-conf node nodes) :> redis-config)
   (c/exec :echo (sentinel-conf node nodes) :> sentinel-config)))

(defn start-redis!
  "Start Redis server"
  []
  (info "Starting Redis")
  (c/su
   (meh (c/exec :killall :-9 :redis-server))
   (c/exec redis-bin redis-config :& :echo $!)))

(defn start-sentinel!
  "Start Redis Sentinel"
  []
  (info "Starting Redis Sentinel")
  (c/su
   (meh (c/exec :killall :-9 :redis-sentinel))
   (c/exec sentinel-bin sentinel-config :& :echo $!)))

(defn stop-redis!
  "Stop Redis server"
  []
  (info "Stopping Redis")
  (c/su
   (meh (c/exec :killall :-9 :redis-server))
   (meh (c/exec :killall :-9 :redis-sentinel))))

(defn wipe!
  "Remove Redis data files"
  []
  (info "Wiping Redis data")
  (c/su
   (meh (c/exec :rm :-rf "/var/lib/redis/dump.rdb"))
   (meh (c/exec :rm :-rf redis-logfile))
   (meh (c/exec :rm :-rf sentinel-logfile))))

(defrecord RedisDB [version]
  db/DB
  (setup! [this test node]
    (info "Setting up Redis on" node)
    (install! version)
    (setup-configs! node (:nodes test))
    (start-redis!)
    (Thread/sleep 5000)  ; Give Redis time to start
    (start-sentinel!)
    (Thread/sleep 5000)  ; Give Sentinel time to start
    )

  (teardown! [this test node]
    (info "Tearing down Redis on" node)
    (stop-redis!)
    (wipe!))

  db/LogFiles
  (log-files [this test node]
    [redis-logfile sentinel-logfile]))

(defn db
  "Creates a Redis DB for the given version"
  [version]
  (RedisDB. version))