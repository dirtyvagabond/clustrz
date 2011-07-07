{\rtf1\ansi\ansicpg1252\cocoartf1038\cocoasubrtf350
{\fonttbl\f0\fswiss\fcharset0 Helvetica;}
{\colortbl;\red255\green255\blue255;}
\margl1440\margr1440\vieww9000\viewh8400\viewkind0
\pard\tx720\tx1440\tx2160\tx2880\tx3600\tx4320\tx5040\tx5760\tx6480\tx7200\tx7920\tx8640\ql\qnatural\pardirnatural

\f0\fs24 \cf0 # clustrz\
\
Clustrz provides a flexible library for handling remote Linux processes.\
\
Clustrz lets you define remote nodes easily, which you can then group into logical clusters. You then have a rich set of functions for inspecting, managing, and otherwise taming these nodes and clusters.\
\
Among other convenience functions, clustrz provides easy ways to interact with JMX-enabled processes.\
\
Higher order functions can be written to perform remote actions on individual nodes. Those functions may then be run against all nodes in a cluster in parallel.\
\
You must have your public key on each node that you wish to work with.\
\
## Usage\
\
First, define your nodes. The minimum required is the remote username and the remote host name or ip address, like so:\
\
	> (def mynode \{:host "some_hostname" :user "some_user"\})\
\
Now you can use various functions with that node. Let's take a look at clustrz functions, starting with the lowest level and working our way up:\
\
	> \
\
\
}