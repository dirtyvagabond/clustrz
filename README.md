# clustrz

Clustrz provides a flexible library for handling remote Linux processes.

Clustrz lets you define remote nodes easily, which you can then group into logical clusters. You then have a rich set of functions for inspecting, managing, and otherwise taming these nodes and clusters.

Among other convenience functions, clustrz provides easy ways to interact with JMX-enabled processes.

Higher order functions can be written to perform remote actions on individual nodes. Those functions may then be run against all nodes in a cluster in parallel.

You must have your public key on each node that you wish to work with.

## Usage

First, define your nodes. The minimum required is the remote username and the remote host name or ip address, like so:

	> (def mynode {:host "some_hostname" :user "some_user"})

Now you can use various functions with that node. Let's take a look at clustrz functions, starting with the lowest level and working our way up:

The <tt>ssh-exec</tt> function let's you run an arbitrary command at a node, and get a hashmap of the results back, like so:

	> (pprint (ssh-exec mynode "uptime"))
	{:exit 0,
 	:out
	 " 14:31:58 up 735 days, 14:34,  0 users,  load average: 0.54, 0.39, 0.27\n",
	 :err ""}

The <tt>shout</tt> function is a convenience function that let's you run an arbitrary command at a node, but just get back standard out. Like so:

	> (shout mynode "uptime")
	"15:31:00 up 735 days, 15:33,  0 users,  load average: 0.76, 0.69, 0.64"



