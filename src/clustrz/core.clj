;;
;; Provides a flexible library for managing remote Linux environments.
;; Treats each environment as a simple "node", and supports grouping
;; nodes into "clusters".
;;
;; Higher order functions can be written to perform remote actions on
;; a node, and then those functions may be run against individual nodes,
;; or against all nodes in a cluster in parallel.
;;
;; You must have your public key on each node that you wish to work with.
;;
(ns clustrz.core
  (:use [clojure.string :only (split split-lines blank? join trim trim-newline)])
  (:use [clojure.java.shell :only (sh)])
  (:use [clojure.pprint :only (pprint)])
  (:use [clojure.contrib.duck-streams :only (make-parents)])
  (:use [clojure.contrib.java-utils :only (file delete-file)])
  (:require [clojure.contrib.jmx :as jmx])
  (:gen-class))

;;; Defines where clustrz stores certain data on nodes.
(def *home* "~/.clustrz/")
(def *kvs-dir* (str *home* "kvs/"))
(def *log* (str *home* "node.log"))

(defn now [] (java.util.Date.))

;;; TODO: consider security; "bash injection" attacks,
;;;       e.g., passing hostile escaped bash code to things like assoc-at.
(defn ssh-exec
  "Runs cmd on the specified node and returns a hashmap of the
   remote result of running the command."
  [{:keys [host user]} cmd]
  (sh "ssh" (str user "@" host) cmd))

(defn shout
  "Runs cmd on node and returns the textual result that went to stdout.
   Throws an exception if the exit status of the command run on the node
   was non-zero."
  [node cmd]
  (let [{:keys [exit out err]} (ssh-exec node cmd)]
    (if (= 0 exit)
      (trim out)
      (throw (Exception. (str "shout error: exit=" exit ", err=\"" (trim-newline err) "\""))))))

(defn ps-map
  "Utility function to parse the textual line output of our custom ps command.
   Returns a hashmap representing the remote process data parsed from the line."
  [line]
  (let [[pid time pctcpu cmd] (split line #"\|")]
    {:pid (Long/parseLong (trim pid))
     :time (trim time)
     :pctcpu (Float/parseFloat (trim pctcpu))
     :cmd cmd}))

(defn ps
  "Fetches data about running processes at node. Returns a list of hashmaps. Each
   hashmap in the list represents a running process. Keys of the map include:
     :pid
     :time
     :pctcpu
     :cmd"
  [node]
  (let [cmd (str "ps --no-header -u " (:user node) " -o \"%p|%x|%C|%a\"")
        lines (split-lines (shout node cmd))]
    (map #(ps-map %) lines)))

(defn mkdir-at
  "Creates the specified path at node. All subdirectories will be created if
   the don't already exist."
  [node path]
  (ssh-exec node (str "mkdir -p " path)))

(defn delete-file-at
  "Deletes the specified file at node."
  [node file]
  (ssh-exec node (str "rm " file)))

(defn last-lines
  "Returns the last n lines from file at node."
  [node file n]
  (shout node (str "tail -" n " " file)))

(defn last-line
  "Returns the last line from file at node."
  [node file]
  (last-lines node file 1))

;; TODO: accidentally trying (execs f a-node) results in opaque error.
;;       should i have just one exec, that asks (seq? nodes) ?
;;       or at least put a precondition on execs that nodes be a seq?

(defn exec
  "Runs f against node, and wraps the result in a hashmap that contains
   extra information about the run. The result of f itself will be at the
   key :out.

   Keys in the returned hashmap include:
     :out  the result of running f on node
     :host  the node's hostname
     :time  how long the run took"
  [f node]
  (let [start (System/currentTimeMillis)
        out (trim (f node))
        t (- (System/currentTimeMillis) start)]
    {:out   out
     :host  (node :host)
     :time  t}))

(defn execs
  "Runs f against all nodes in parallel. Returns a sequence of results,
   where each element in the sequence is a result from running f on one
   of the nodes."
  [f nodes]
  (doall (apply pcalls (map #(partial f %) nodes))))

(defn uptime-at
 "Returns the raw uptime string from node."
 [node]
 (shout node "uptime"))

(defn nice-report-str
  [hashmaps]
  (join "\n" (map #(str (:host %) ": " (:out %)) hashmaps)))

(defn nice-seq [thing]
  (if (seq? thing) thing (list thing)))

;;TODO: is it goofy to transparenty treat a node as a cluster?
;;      this means that, e.g., ($ f a-single-node) will return
;;      a sequence, so the caller will need to call first
;;      to get just the result. But I don't know if I like having
;;      one function that expects a single node, and another
;;      function that expects nodes, like the exec and execs
;;      functions... this can get tedious when writing calling
;;      code.
(defn $
  "Runs f across all nodes in parallel. Each result is wrapped in
   a hashmap per our custom exec function, and returned in a sequence."
  [f nodes]
  (doall (apply pcalls (map #(partial exec f %) (nice-seq nodes)))))

(defn report [fn nodes]
  (nice-report-str ($ fn nodes)))

(defn tmp-file []
  (str "/tmp/clustrz_tmp_" (java.util.UUID/randomUUID)))

(defn copy-to
  "Copies local-file to the specified host destination, copying it
   to the file path specified by dest-file."
  [local-file {:keys [host user]} dest-file]
    (sh "scp" local-file (str user "@" host ":" dest-file)))

(defn copy-files-to
  "Copies local files to the specified destination folder on the
   specified remote host."
  [files {:keys [host user]} dest-path]
  (let [dest (str user "@" host ":" dest-path)]
    (apply sh (flatten ["scp" files dest]))))

(defn spit-at
  "Puts the textual representation of val in dest-file at node.
   The file will be overwritten if it already exists."
  [node dest-file val]
  (let [tmp-local-file (tmp-file)]
    (spit tmp-local-file (str val))
    (let [res (copy-to tmp-local-file node dest-file)]
      (delete-file tmp-local-file)
      res)))

(defn append-spit-at
  "Appends the textual representation of s to dest-file at node."
  [node dest-file s]
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
  ;;; OPTIMIZE: mkdir is only needed once per node, and only if the dir isn't there. how to track?
  (mkdir-at node *kvs-dir*)
  (spit-at node (kvs-file key) (with-out-str (pr val))))

(defn get-at
  ([node key not-found]
     "Returns the object associated with key at node, or not-found if none."
     (let [out (:out (ssh-exec node (str "cat " (kvs-file key))))]
       (if (= 0 (.length out))
         not-found
         (read-string out))))
  ([node key]
     "Returns the object associated with key at node, or nil if none."
     (get-at node key nil)))

(defn dissoc-at
  "Removes key at node."
  [node key]
  (delete-file-at node (kvs-file key)))

;;; TODO: creates a DateFormat each time for thread safety. better way?
(defn bash-time
  "Converts a bash time string to a java Date.
   Example input, t: 'Fri Dec 3 02:51:12 PST 2010'"
  [t]
  (let [df (java.text.SimpleDateFormat. "EEE MMM d HH:mm:ss z yyyyy")]
    (.parse df t)))

(defn slurp-at
  [node file]
  "Returns the contents of file at node."
  (shout node (str "cat " file)))

(defn log-at
  [node msg]
  "Appends msg to the central log stored at node."
  (append-spit-at node *log* (str (now) ": " msg "\n")))

(defn log [msg]
  (println (str (now) ": " msg)))

(defn log2 [node msg]
  (log-at node msg)
  (log (str (node :host) ": " msg)))

(defn up?
  [{:keys [user host pid] :as node}]
  "Returns true if and only if the specified remote process is
   running."
  (let [out (shout node (str "ps --no-header -p " pid))]
    (not (blank? out))))

(def down?
  (complement up?))

(defn java?
  [proc]
  "Returns true if and only if the specified remote
   process is a Java process."
  (not (nil? (re-matches #".*java" (first (split (proc :cmd) #"\s"))))))

(defn clojure?
  [proc]
  "Returns true if and only if the specified remote process is a
   Clojure process."
  (not (nil? (re-matches #".* clojure\.main .*" (proc :cmd)))))

(defn chmod-at [node opts file]
  (ssh-exec node (str "chmod " opts " " file)))

(defn wget-at
  ([node url dest-dir]
     "Runs wget at node for the specified url, with pwd set to dest-dir."
     (wget-at node url dest-dir ""))
  ([node url dest-dir opts]
     "Runs wget at node for the specified url, with pwd set to dest-dir.
      opts must be valid options as one string, or an empty string."
     (ssh-exec node (str "cd " dest-dir "; wget " opts " " url))))

(defn tagger
  [node key f]
  "Associates key to node, where the value of key is the result of
   calling f on node."
  (assoc node key (f node)))

(defn >+ [f nodes]
  (doall (apply pcalls (map
                  #(partial tagger % (keyword (:name (meta f))) f)
                  (nice-seq nodes)))))

;;
;; JMX related
;;

(defn jmx-props [node]
  {:host (:host node),
   :port (get-in node [:jmx :port]),
   :environment {"jmx.remote.credentials" (into-array [(get-in node [:jmx :user])
                                                       (get-in node [:jmx :pwd])])}})

(defn jmx-names
  [node]
  "Returns a sequence of JMX ObjectNames, where each ObjectName
   represents a JMX mbean available at node. This can be used to
   discover the full set of mbeans available at node."
  (into #{}
   (jmx/with-connection (jmx-props node)
     (jmx/mbean-names "*:*"))))

(defn jmx-type-at
  ([node package type]
     "Returns the JMX mbean available at node for type,
      under package."
     (jmx/with-connection (jmx-props node)
       (jmx/mbean (str package ":type=" type))))
  ([node type]
     "Returns the JMX mbean available at node for type,
      under the java.lang package."
     (jmx-type-at node "java.lang" type)))

(defn start-time-at
  [proc]
  "Returns a Java Date representing the time that the specified remote
   JMX-enabled process was started."
  (java.util.Date.
    (:StartTime
      (jmx-type-at proc "Runtime"))))

(defn os-at
  [proc]
  "Returns the OperatingSystem mbean data for the JMX-enabled proc."
  (jmx-type-at proc "OperatingSystem"))

(defn load-avg-at
  [proc]
  "Returns the load average for the OS running the JMX-enabled proc,
   as a Double."
  (:SystemLoadAverage
    (os-at proc)))

(defn threading-at
  [proc]
  "Returns the Threading mbean data for the JMX-enabled proc."
  (jmx-type-at proc "Threading"))

;;
;; Factual/Quartz specific
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

(comment
  (defmacro def-hosts [cluster]
    (let [node (gensym "node")]
      `(doseq [~node ~cluster]
         (def ~(symbol (str (:host `~node))) ~node)))))

;; A sample vote server node
(def vot (first quartz))

(defn vote-servers-at [node]
  (filter vote-server? (ps node)))

;; TODO: ambiguous if >1 vs is running  :-/
(defn pct-cpu-at [node]
  (:pctcpu
    (first (vote-servers-at node))))

(defn restart-vs [node]
  (shout node "/u/apps/PRODUCTION/quartz/shared/bin/vot_restart.sh"))

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
