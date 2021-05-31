# WYR2 Design Goals

* I can easily see which key belongs to which user.
* I can easily associate a client with multiple servers, or a server with multiple peers.
* I can configure subnet routes from the same interface.
* (maybe someday) I can configure ACLs from that interface.

What if there were a single EDN file with the "network map".  And the tool would generate the commands you needed to run on each machine.  But sometimes we have weird ways of connecting to each machine.

* A preset from a central location, showing what servers to connect to.
* Run the connection command on a client to generate the keys.
* Then approve said command on the server.

Here's an insight.  Each client only needs to have the fancy firewall stuff for one server.  The other servers are just backup means of connection.
