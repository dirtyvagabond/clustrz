(ns clustrz.core
  (:use [clojure.string :only (split split-lines blank? join trim trim-newline)])
  (:use [clojure.java.shell :only (sh)])
  (:use [clojure.pprint :only (pprint)])
  (:use [clojure.contrib.duck-streams :only (make-parents)])
  (:use [clojure.contrib.java-utils :only (file delete-file)])
  (:require [clojure.contrib.jmx :as jmx])
  (:gen-class))

(def *home* "~/.clustrz/")
(def *kvs-dir* (str *home* "kvs/"))
(def *log* (str *home* "node.log"))

(defn now [] (java.util.Date.))

;; TODO: consider security; "bash injection" attacks,
;;       e.g., passing hostile escaped bash code to things like assoc-at.
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
        out (trim (f node))
        t (- (System/currentTimeMillis) start)]
    {:out   out
     :host  (node :host)
     :time  t}))

(defn execs [f nodes]
  (doall (apply pcalls (map #(partial f %) nodes))))

(defn nice-report-str [hashmaps]
  (join "\n" (map #(str (:host %) ": " (:out %)) hashmaps)))

;;TODO: is it goofy to transparenty treat a node as a cluster?
;;      this means that, e.g., ($ f a-single-node) will return
;;      a sequence, so the caller will need to call first
;;      to get just the result. But I don't know if I like having
;;      one function that expects a single node, and another
;;      function that expects nodes, like the exec and execs
;;      functions... this can get tedious when writing calling
;;      code.
(defn $ [f nodes]
  (let [nodes (if (seq? nodes) nodes (list nodes))]
    (doall (apply pcalls (map #(partial exec f %) nodes)))))

(defn report [fn nodes]
  (nice-report-str ($ fn nodes)))

(defn tmp-file []
  (str "/tmp/clustrz_tmp_" (java.util.UUID/randomUUID)))

(defn scp
  [local-file {:keys [host user]} dest-file]
  (sh "scp" local-file (str user "@" host ":" dest-file)))

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

(defn slurp-at [node file]
  (shout node "cat " file))

(defn kvs-file [key]
  (str *kvs-dir* key))

(defn assoc-at
  "Associates val with key, at node. val can be any Clojure object."
  [node key val]
  ;;optimize: mkdir is only needed once per node, and only if the dir isn't there. how to track?
  (mkdir-at node *kvs-dir*)
  (spit-at node (kvs-file key) (with-out-str (pr val))))

(defn get-at
  "Returns the object associated with key at node, or nil if none."
  [node key]
  (read-string
    (shout node (str "cat " (kvs-file key)))))

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

(defn wget-at
  ([node url dest-dir]
    (wget-at node url dest-dir ""))
  ([node url dest-dir opts]
     "Runs wget at node for the specified url, with pwd set to dest-dir.
      opts must be valid options as one string, or an empty string."
    (ssh-exec node (str "cd " dest-dir "; wget " opts " " url))))

;;
;; JMX related
;;

(defn jmx-props [node]
  {:host (:host node),
   :port (get-in node [:jmx :port]),
   :environment {"jmx.remote.credentials" (into-array [(get-in node [:jmx :user])
                                                       (get-in node [:jmx :pwd])])}})

(defn jmx-names [node]
  (into #{}
   (jmx/with-connection (jmx-props node)
     (jmx/mbean-names "*:*"))))

(defn jmx-type-at [node type]
  (jmx/with-connection (jmx-props node)
    (jmx/mbean (str "java.lang:type=" type))))

(defn start-time-at [node]
  (:StartTime
   (jmx-type-at node "Runtime")))

(defn threading-at [node]
  (jmx-type-at node "Threading"))

;;
;; Quartz specific
;;

(defn vote-server? [proc]
  (and
   (java? proc)
   (not (nil? (re-matches #".* quartz.voteserver.rest.VoteServerRestBootstrap .*" (proc :cmd))))))

(def oome-log "/u/apps/PRODUCTION/quartz/shared/bin/oome.log")

(def vot-hosts (map #(str "vot0" %) ["04" "05" "06" "07" "09" "14" "10" "11" "12" "13"]))

(def quartz-props {:user "rails_deploy",
                     :jmx {:port 8021, :user "monitorRole", :pwd "quartz"}})

;; Quartz vote servers. Every host has the same properties (except host name).
(def quartz (map #(merge quartz-props {:host %}) vot-hosts))

;; A sample vote server node
(def vot (first quartz))

(defn restart-vs [node]
  (shout node "/u/apps/PRODUCTION/quartz/shared/bin/vot_restart.sh"))

;;TODO: otherwise, check-oome breaks because get-at triggers an exception due to file not found;
;;      need a good way for get-at to return nil in this case instead.
(defn prep-oome-check [node]
  (assoc-at node :last-seen-oome "Fri Dec 3 02:22:22 PST 2008"))

(defn new-oome-vs [node oome-date-str]
  (log2 node (str "Found new oome: " oome-date-str))
  (restart-vs node)
  (log2 node "Restarted VoteServer")
  (assoc-at node :last-seen-oome oome-date-str))

(defn get-last-seen-oome [node]
  (bash-time (get-at node :last-seen-oome)))

(defn check-oome [node]
  (let [last-oome-str (last-line node oome-log)
        last-oome (bash-time last-oome-str)
        last-seen-oome (get-last-seen-oome node)]
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
