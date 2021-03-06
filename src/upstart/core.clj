(ns upstart.core
  (:use [clojure.string :only (split split-lines blank? join trim trim-newline)])
  (:use [clojure.java.shell :only (sh)])
  (:use [clojure.pprint :only (pprint)]))

(def *home* "~/.clustrz/")

(def *bash-date-format* (java.text.SimpleDateFormat. "EEE MMM d HH:mm:ss z yyyyy"))

(defn ssh-exec [{:keys [host user]} cmd]
  (sh "ssh" (str user "@" host) cmd))

(defn ls [node path]
  (ssh-exec node (str "ls " path)))

(defn java? [proc]
  (not (nil? (re-matches #".*java" (first (split (proc :cmd) #"\s"))))))

(defn clojure? [proc]
  (not (nil? (re-matches #".* clojure\.main .*" (proc :cmd)))))

(defn shout
  "Runs cmd on node and returns the textual result that went to stdout."
  [node cmd]
  ;;(println "shout cmd:" cmd)
  (let [res (ssh-exec node cmd)]
    (if (blank? (res :err))
      (trim-newline (res :out))
      ; TODO: Throw SshException, which contains res? or encode res as clj str?
      (throw (Exception. (str "shout error: " (res :err)))))))

(defn ps-map [line]
  (let [[pid time pctcpu cmd] (split line #"\|")]
    {:pid (Long/parseLong (trim pid))
     :time (trim time)
     :pctcpu (Float/parseFloat (trim pctcpu))
     :cmd cmd}))

(defn ps [node]
  "Fetches data about running processes at node. Returns a list of hash maps. Each
   hashmap in the list represents a running process. Keys of the map include:
   :pid
   :time
   :pctcpu
   :cmd"
  (let [cmd (str "ps --no-header -u " (:user node) " -o \"%p|%x|%C|%a\"")
        lines (split-lines (shout node cmd))]
    (map #(ps-map %) lines)))

(defn uptime
 "Fetches the raw uptime string from node."
 [node]
 (shout node "uptime"))

(defn user-hosts [user hosts]
  (map #(hash-map :user user :host %) hosts))

;; TODO!: so there's a conceptual difference between a host (e.g., "vot013")
;; and a node (e.g., "my vineyard consumer on vot013"), and a process (e.g.,
;; "pid 1071, which is running my vineyard consumer on vot013")

(defn last-line [node file]
  (shout node (str "tail -1 " file)))

(defn up? [{:keys [user host pid]}]
  (let [ps-out (sh "ssh" (str user "@" host) (str "ps --no-header -p " pid))]
    (not (blank? ps-out))))

(def down?
  (complement up?))

(defn stop! [node] true)

(defn start! [node] true)

(defn restart! [node]
  (println "restart!" node)
  (stop! node)
  (start! node))

(defn tshout [node cmd]
  (let [start (System/currentTimeMillis)
        out (shout node cmd)
        t (- (System/currentTimeMillis) start)]
    {:host (node :host) :out out :time t}))

(defn exec [f node]
  (let [start (System/currentTimeMillis)
        out (f node)
        t (- (System/currentTimeMillis) start)]
    {:host (node :host) :out out :time t}))

(defn tshouts [nodes cmd]
  (pmap #(tshout % cmd) nodes))

(defn execs [f nodes]
  (pmap #(exec f %) nodes))

(defn mkdir [node file]
  (ssh-exec node (str "mkdir -p " file)))

(defn nput [node key val]
  (mkdir node (str *home* "kvs"))
  (let [file (str "~/.clustrz/kvs/" key)]
    (ssh-exec node (str "rm " file ";echo \"" (str val) "\" > " file))))

(defn nget [node key]
  (let [file (str "~/.clustrz/kvs/" key)]
    (shout node (str "cat " file))))

(defn bash-time
  "Converts a bash time string to a java Date.
   Example input, t: 'Fri Dec 3 02:51:12 PST 2010'"
  [t]
  (.parse *bash-date-format* t))

;;
;; Quartz specific
;;

(defn vote-server? [proc]
  (and
   (java? proc)
   (not (nil? (re-matches #".* quartz.voteserver.rest.VoteServerRestBootstrap .*" (proc :cmd))))))

(def *oome-log* "/u/apps/PRODUCTION/quartz/shared/bin/oome.log")

(def vot013
  {:host "vot013"
   :user "rails_deploy"})

(def quartz (user-hosts "rails_deploy"
                        ["vot004"
                         "vot005"
                         "vot006"
                         "vot007"
                         "vot009"
                         "vot014"
                         "vot010"
                         "vot011"
                         "vot012"
                         "vot013"]))

(defn last-oome [node]
  (bash-time ))

(defn restart-vs [node]
  (shout node "/u/apps/PRODUCTION/quartz/shared/bin/vot_restart.sh"))

;;TODO: otherwise, check errors out because nget triggers an exception due to file not found;
;;      need a good way for nget to return nil in this case instead.
(defn prep-oome-check [node]
  (nput node "last-seen-oome" "Fri Dec 3 02:22:22 PST 2008"))

(defn check-oome [node]
  (let [last-oome-str (last-line node *oome-log*)
        last-oome (bash-time last-oome-str)
        last-seen-oome (bash-time (nget node "last-seen-oome"))]
    (if (.after last-oome last-seen-oome)
      (do
        (println "New OOME!" last-oome-str)
        (restart-vs node)
        (println "RESTARTED " (node :host))
        (nput node "last-seen-oome" last-oome-str)
        (println "updated last-seen-oome"))
      (println "Same OOME, ho hum"))))
