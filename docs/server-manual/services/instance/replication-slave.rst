Replication Slave
==================


The slave counterpart to the :doc:`replication-master`.  It receives serialized tuple data from the master and injects it in the local stream. Works both in TCP server and TCP client mode. In TCP server mode, it relies on the :doc:`../global/replication-server` to provide the TCP connectivity. 
In TCP client mode, it connects to the master defined in the configuration.

The slave keeps track of the id of the last transaction received from the master in a local text file ``yamcs-data/<instance>/replication/slave-lastid.txt``. Each time the connection to the master is estabilished, it sends a request containing the last transaction id +1. The master will start replaying data from that transaction. If the replication slave does not find the file at startup, it will receive all the data that the master has.

There can be two or replication slaves running for the same instance, connected to two different masters.

To avoid an infinite message flood caused by a missconfiguration whereby a slave receives and inserts into a stream the data which was extracted from the same stream, each incoming messages contains a 32 bit ``instance id``. This is the id of the instance where the message has originated from. If a slave receives a message with its own instance id it will discard it and not insert it into the stream.
The instance id is calculated as a hash code from the ``<serverId>.<instanceName>``. The serverId is by default the hostname but can be changed in yamcs.yaml.


Class Name
----------

:javadoc:`org.yamcs.replication.ReplicationSlave`


Configuration
-------------

This service is defined in ``etc/yamcs.(instance).yaml``. Example:

.. code-block:: yaml

  services:
      - class: org.yamcs.replication.ReplicationSlave
        args:
            tcpRole: client
            masterHost: localhost
            masterPort: 8099
            masterInstance: node1
            enableTls: false
            reconnectionIntervalSec: 30
            streams: ["tm_realtime", "sys_param"]
            lastTxFile: "slave-lastid.txt"
            

              
Configuration Options
---------------------

tcpRole  (string)
    **Required** One of client or server.

masterHost (string)
    **Required** if the tcpRole is `client`. The hostname of the master. Not relevant if the tcpRole is `server`.
    
masterPort (integer)
    **Required** if the tcpRole is `client`. The port of the master.  Not relevant if the tcpRole is `server`.
    
masterInstance (string)
    **Required** if the tcpRole is `client`. The instance of the master. When working in `server` tcp mode, the instance on which the master is configured determines the data which will be passed to the slave. If two masters try to connect to the same slave, only the first connection will be accepted. 

enableTls (boolean)
     Used when tcpRole is `client`. If true, a TLS connection will be attempted. The server provided certificate will be checked against the trustStore in yamcs/etc/ directory. If the tcpRole is `server` the usage or not of TLS is determined by the configuration of the :doc:`../global/replication-server`.
     
reconnectionInterval (integer)
    If the tcpRole is `client` this configures how often in seconds the slave will try to connect to the master if the connection is broken. A negative value means that no reconnection will take place.
               
streams (list of strings)
    The list of streams that will be processed. The master may send data from other streams but they will be filtered out.

lastTxFile (String)
    The name of file where the slave will keep track of the last transaction id received from the server. It defaults to the ``<service-name>-lastid.txt``

maxTupleSize (integer)
    Used in case the tcpRole = client - configures the maximum size of one message received from the master.  If the serialized tuples are larger than this size, this limit has to be increased otherwise the tuples cannot be transferred. Default 131072 (128KB).
