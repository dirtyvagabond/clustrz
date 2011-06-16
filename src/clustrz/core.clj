(ns clustrz.core
  (:use [clojure.string :only (split split-lines blank? join trim trim-newline)])
  (:use [clojure.java.shell :only (sh)])
  (:use [clojure.pprint :only (pprint)])
  (:use [clojure.contrib.duck-streams :only (make-parents)])
  (:use [clojure.contrib.java-utils :only (file delete-file)])
  (:gen-class))

(def *home* "~/.clustrz/")
(def *kvs-dir* (str *home* "kvs/"))
(def *log* (str *home* "node.log"))

(defn now [] (java.util.Date.))

(defn ssh-exec [{:keys [host user]} cmd]
  (sh "ssh" (str user "@" host) cmd))

(defn shout
  "Runs cmd on node and returns the textual result that went to stdout.
   Throws an exception if the exit status of the command run on the node
   was non-zero."
  [node cmd]
  (let [{:keys [exit out err]} (ssh-exec node cmd)]
    (if (= 0 exit)
      (trim-newline out)
      (throw (Exception. (str "shout error: exit=" exit ", err=\"" (trim-newline err) "\""))))))

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

(defn mkdir-at [node file]
  (ssh-exec node (str "mkdir -p " file)))

(defn delete-file-at [node file]
  (ssh-exec node (str "rm " file)))

(defn user-hosts [user hosts]
  (map #(hash-map :user user :host %) hosts))

(defn last-lines [node file n]
  (shout node (str "tail -" n " " file)))

(defn last-line [node file]
  (last-lines node file 1))

;; TODO: accidentally trying (execs f a-node) results in opaque error.
;;       should i have just one exec, that asks (seq? nodes) ?
;;       or at least put a precondition on execs that nodes be a seq?

(defn exec [f node]
  "Runs f against node, and wraps the result in a hash map that contains
   extra information about the run. The result of f itself will be at the
   key :out. Other keys include:
   :host  the node's hostname
   :time  how long the run took"
  (let [start (System/currentTimeMillis)
        out (f node)
        t (- (System/currentTimeMillis) start)]
    {:host (node :host) :out out :time t}))

(defn execs [f nodes]
  (doall (pmap #(exec f %) nodes)))

(defn nice-report-str [hashmaps]
  (join "\n" (map #(str (:host %) ": " (:out %)) hashmaps)))

(defn tmp-file []
  (str "/tmp/clustrz_tmp_" (java.util.UUID/randomUUID)))

(defn scp
  [local-file {:keys [host user]} dest-file]
  (let [args (str local-file " " user "@" host ":" dest-file)]
    (sh "scp" local-file (str user "@" host ":" dest-file))))

(defn spit-at [node dest-file val]
  (let [tmp-local-file (tmp-file)]
    (spit tmp-local-file (str val))
    (let [res (scp tmp-local-file node dest-file)]
      (delete-file tmp-local-file)
      res)))

(defn append-spit-at [node dest-file s]
  (let [tmp-dest-file (tmp-file)]
    (spit-at node tmp-dest-file s)
    (ssh-exec node (str "cat " tmp-dest-file " >> " dest-file "; rm " tmp-dest-file))))

(defn kvs-file [key]
  (str *kvs-dir* key))

(defn assoc-at [node key val]
  ;;optimize: mkdir is only needed once per node, and only if the dir isn't there. how to track?
  (mkdir-at node *kvs-dir*)
  (spit-at node (kvs-file key) (str val)
  ;;         (if (string? val) (str "\"" val "\"") val)
           )
  )

(defn get-at [node key]
  (shout node (str "cat " (kvs-file key))))

(defn dissoc-at [node key]
  (delete-file-at node (kvs-file key)))

;; TODO: creates a DateFormat each time for thread safety. better way?
(defn bash-time
  "Converts a bash time string to a java Date.
   Example input, t: 'Fri Dec 3 02:51:12 PST 2010'"
  [t]
  (let [df (java.text.SimpleDateFormat. "EEE MMM d HH:mm:ss z yyyyy")]
    (.parse df t)))

(defn slurp-at [node file]
  (shout node (str "cat " file)))

(defn log-at [node msg]
  (append-spit-at node *log* (str (now) ": " msg "\n")))

(defn log [msg]
  (println (str (now) ": " msg)))

(defn log2 [node msg]
  (log-at node msg)
  (log (str (node :host) ": " msg)))

(defn up? [{:keys [user host pid] :as node}]
  (let [out (shout node (str "ps --no-header -p " pid))]
    (not (blank? out))))

(def down?
  (complement up?))

(defn java? [proc]
  (not (nil? (re-matches #".*java" (first (split (proc :cmd) #"\s"))))))

(defn clojure? [proc]
  (not (nil? (re-matches #".* clojure\.main .*" (proc :cmd)))))

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

(def vot004
  {:host "vot004"
   :user "rails_deploy"})

(def quartz
  (user-hosts "rails_deploy"
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

(defn restart-vs [node]
  (shout node "/u/apps/PRODUCTION/quartz/shared/bin/vot_restart.sh"))

;;TODO: otherwise, check errors out because get-at triggers an exception due to file not found;
;;      need a good way for get-at to return nil in this case instead.
(defn prep-oome-check [node]
  (assoc-at node "last-seen-oome" "Fri Dec 3 02:22:22 PST 2008"))

(defn new-oome-vs [node oome-date-str]
  (log2 node "Found new oome:" oome-date-str)
  (restart-vs node)
  (log2 node "Restarted VoteServer")
  (assoc-at node "last-seen-oome" oome-date-str))

(defn check-oome [node]
  (let [last-oome-str (last-line node *oome-log*)
        last-oome (bash-time last-oome-str)
        last-seen-oome (bash-time (get-at node "last-seen-oome"))]
    (if (.after last-oome last-seen-oome)
      (do
        (new-oome-vs node last-oome-str)
        {:new-oome last-oome})
      (do
        (log2 node "No new oomes")
        {:new-oome false}))))

(defn -main []
  (log "Checking all vote servers for oomes...")
  (execs check-oome quartz))
