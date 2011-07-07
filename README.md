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

	> 
