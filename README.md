# clustrz

clustrz provides a flexible library for handling remote Linux processes. It lets you define remote nodes easily, which you can then group into logical clusters. You then have a rich set of functions for inspecting, managing, and otherwise taming these nodes and clusters.

Among other convenience functions, clustrz provides easy ways to interact with JMX-enabled processes.

Higher order functions can be written to perform remote actions on individual nodes. Those functions may then be run against all nodes in a cluster in parallel.

You must have your public key on each node that you wish to work with.

## Basic Usage

First, define your nodes. The minimum required is the remote username and the remote host name or ip address, like so:

	> (def mynode {:host "some_hostname" :user "some_user"})

Now you can use various functions with that node. Let's take a look at clustrz functions, starting with the lowest level and working our way up:

The <tt>ssh-exec</tt> function let's you run an arbitrary command at a node, and get a hashmap of the results back, like so:

	> (pprint (ssh-exec mynode "uptime"))
	{:exit 0,
 	 :out " 14:31:58 up 735 days, 14:34,  0 users,  load average: 0.54, 0.39, 0.27\n",
	 :err ""}

The <tt>shout</tt> function is a convenience function that let's you run an arbitrary command at a node, but just get back standard out. Like so:

	> (shout mynode "uptime")
	"15:31:00 up 735 days, 15:33,  0 users,  load average: 0.76, 0.69, 0.64"

Let's create a first class function for uptime:

	(defn uptime-at
	 "Returns the raw uptime string from node."
	 [node]
	 (shout node "uptime"))

Note that clustrz comes with <tt>uptime-at</tt> already defined. So now we can do this:

	> (uptime-at mynode)
	"15:35:16 up 735 days, 15:37,  0 users,  load average: 0.53, 0.77, 0.69"

This is cool because we've created a first class function that can be composed with other useful stuff. For example, the <tt>exec</tt> function takes a first class function, runs it against a specified node, then wraps the output of that function with extra useful information about the remote action. For example:

	> (pprint (exec uptime-at mynode))
	{:out "15:37:56 up 735 days, 15:40,  0 users,  load average: 0.40, 0.59, 0.63",
	 :host "some_hostname",
	 :time 511}

The <tt>:out</tt> entry contains the result of running <tt>uptime-at</tt> at <tt>mynode</tt>, which has interesting info in and of itself. The <tt>:host</tt> lets you remember where you ran the function, and <tt>:time</tt> tells you how many milliseconds it took round-trip to run the function for that host.

Let's take a look at working with a cluster. Define your cluster as a sequence of nodes:

	> (def mycluster (list
	  {:host "some_hostname1" :user "some_user"}
	  {:host "some_hostname2" :user "some_user"}
	  {:host "some_hostname3" :user "some_user"})

The <tt>$</tt> function runs a function over a cluster of nodes using <tt>exec</tt>. For example:

	> (pprint ($ uptime-at mycluster))
	({:out "17:49:38 up 717 days,  2:19,  1 user,  load average: 0.43, 0.58, 0.57",
	  :host "some_hostname1",
	  :time 889}
	 {:out "17:49:38 up 735 days, 17:51,  0 users,  load average: 0.22, 0.48, 0.60",
	  :host "some_hostname2",
	  :time 507}
	 {:out "17:49:38 up 388 days, 22:04,  1 user,  load average: 0.55, 0.48, 0.48",
	  :host "some_hostname3",
	  :time 503})

Since <tt>exec</tt> returns a well-defined hashmap, and therefore <tt>$</tt> returns a sequence of well-defined hashmaps, you can easily build on top of <tt>$</tt>.

For example, let's write a function that takes the output from <tt>$</tt> and simplifies it down to a cleanly formatted textual report:

	> (defn nice-report-str [hashmaps]
  	    (join "\n" (map #(str (:host %) ": " (:out %)) hashmaps)))

Now we can do this:

	> (println (nice-report-str ($ uptime-at mycluster)))
	some_hostname1: 17:56:20 up 717 days,  2:26,  1 user,  load average: 0.54, 0.55, 0.55
	some_hostname2: 17:56:21 up 735 days, 17:58,  0 users,  load average: 0.18, 0.36, 0.50
	some_hostname3: 17:56:21 up 388 days, 22:10,  1 user,  load average: 0.68, 0.55, 0.49

Note that clustrz comes with <tt>nice-report-str</tt> already defined.

## Talking JMX

clustrz comes with functions for interacting with a remote process via JMX. To use these functions you must define your nodes to include some additional JMX configuration:

	> (def dynode {:host "some_hostname1",
	               :user "some_user",
	               :jmx {:port 8021, :user "monitorRole", :pwd "sequestastronomy"}}

Now let's see what mbeans are available at our remote process:

	> (print (jmx-names mynode))
	#{#<ObjectName java.lang:type=GarbageCollector,name=ParNew>
	  #<ObjectName java.lang:type=Compilation>
	  #<ObjectName java.lang:type=MemoryPool,name=Par Survivor Space>
	  #<ObjectName java.lang:type=MemoryPool,name=CMS Old Gen>
	  #<ObjectName java.lang:type=Memory>
	  #<ObjectName java.lang:type=GarbageCollector,name=ConcurrentMarkSweep>
	  #<ObjectName java.lang:type=ClassLoading>
	  #<ObjectName JMImplementation:type=MBeanServerDelegate>
	  #<ObjectName java.lang:type=MemoryManager,name=CodeCacheManager>
	  #<ObjectName java.lang:type=OperatingSystem>
	  #<ObjectName java.lang:type=MemoryPool,name=Par Eden Space>
	  #<ObjectName java.lang:type=MemoryPool,name=Code Cache>
	  #<ObjectName java.lang:type=Threading>
	  #<ObjectName java.lang:type=Runtime>
	  #<ObjectName java.util.logging:type=Logging>
	  #<ObjectName com.sun.management:type=HotSpotDiagnostic>
	  #<ObjectName java.lang:type=MemoryPool,name=CMS Perm Gen>}

Lot's of interesting possibilities there! Let's see what the Threading mbean is all about:

	> (pprint (jmx-type-at mynode "java.lang" "Threading"))
	{:TotalStartedThreadCount 218,
	 :ThreadCpuTimeEnabled true,
	 :DaemonThreadCount 13,
	 :ThreadCpuTimeSupported true,
	 :SynchronizerUsageSupported true,
	 :ObjectMonitorUsageSupported true,
	 :CurrentThreadCpuTime 0,
	 :ThreadCount 45,
	 :ThreadContentionMonitoringSupported true,
	 :ThreadContentionMonitoringEnabled false,
	 :AllThreadIds
	 [225, 223, 222, 221, 216, 215, 214, 212, 211, 210, 209, 208, 207, 204,
	  202, 200, 199, 197, 195, 194, 193, 192, 190, 189, 187, 182, 176, 169,
	  165, 162, 138, 133, 74, 27, 23, 18, 17, 14, 13, 12, 11, 10, 5, 3, 2],
	 :CurrentThreadUserTime 0,
	 :CurrentThreadCpuTimeSupported true,
	 :PeakThreadCount 60

There's some intriguing data there. This is just a simple hashmap structure at this point, so we can easily pull out whatever specific thing we're interested in:

	> (:ThreadCount (jmx-type-at mynode "java.lang" "Threading"))
	45

