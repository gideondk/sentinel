# Sentinel

![Sentinel](http://images.wikia.com/matrix/images/c/c2/Sentinel_Print.jpg)

## Overview

**Sentinel** is boilerplate for the creation of reactive TCP based servers and clients through Akka IO (2.2).

It focusses on raw performance by using reactive patterns through multiple sockets (at once) represented by multiple workers, both client / server side.

In it's current state, it's being used internally as a platform to test performance strategies for CPU and IO bound services. But will fuel both [Raiku](http://github.com/gideondk/raiku) as other soon-to-be-released Akka based libraries in the future.


## Status

Since the IO layer (and its API) in Akka 2.2 isn't stable yet, the current codebase of Sentinel can and will change heavily until the release of Akka 2.2.

Sentinel will stay as close to Akka as possible, so the (legacy) Iteratee library of Akka is currently used to handle parsing of network messages. Because of this dependency, code may and will break if Sentinel switches to a different Iteratee library (depending if the current Iteratee library stays in Akka or not).

In overall, treat Sentinel as pre-release alpha software.

**Currently available in Sentinel:**

* Easy creation of reactive TCP servers / clients;
* Easy initialisation of servers and clients for default or custom router worker strategies;
* Supervision (and restart / reconnection functionality) on both server and client `traits` for a defined number of worker children;
* Default implementations for both Ack as Noack based flow control;
* Sequencing and continuing multiple client operations using monad transformers (ValidatedFuture, ValidatedFutureIO).

The following is currently missing in Sentinel, but will be added soon:

* More robust benchmarks for CPU / IO bound services to test router / worker strategies;
* Better error handling and recovery;
* Server to client communication;
* More examples, and overall awesomeness…

## Installation
You can install Sentinel through source (by publishing it into your local Ivy repository):

	./sbt publish-local
	
(Repository will follow soon…)

## Usage
Usage of Sentinel is very straight forward, a Client class is created by extending  the `SentinelClientWorker` trait and implementing the `processRequest` Iteratee: 

	class PingClientWorker extends SentinelClientWorker {
	  val writeAck = false
	  val workerDescription = "Ping Client Worker"
	  val processRequest = for {
	    bs ← akka.actor.IO.take(4) // "PONG"
	  } yield new String(bs.toArray)
	}

The creation of a server is done in the same fashion: 

	class PingServerWorker extends SentinelServerWorker {
	  val writeAck = false
	  val workerDescription = "Ping Server Worker"
	  val processRequest = for {
	    bs ← akka.actor.IO.take(4) // "PING"
	  } yield {
	    val builder = new ByteStringBuilder
	    builder.putBytes("PONG".getBytes)
	    builder.result
	  }
	}

### Initialisation
The initialisation of the client is done through the `SentinelClient` helper): 
	
	SentinelClient.randomRouting[PingClientWorker](HOSTNAME, PORT, 
		WORKER_COUNT, CLIENT_DESCRIPTION)
		
You can use the `randomRouting` / `roundRobinRouting` methods depending on the routing strategy you want to use to communicate to the workers. For a more custom approach the `apply` method is available, which lets you define a router strategy yourself. 

Initialisation of a `SentinelServer` follows roughly the same API: 

	SentinelServer.randomRouting[PingServerWorker](PORT, WORKER_COUNT,
		SERVER_DESCRIPTION)

### Client usage

Once a client and / or server has been set up, the `??` method can be used on the client to send a command to the connected server. A `ByteString` is send to the client to form the command send to the server, returning a `ValidatedFutureIO[Any]` for the possible response of the server.

The `ValidatedFutureIO[Any]` type is extended with the functionality to (safely) cast the `Any` type back to the type you intend to retrieve from the `SentinelClient`. If a cast is invalid, the error is automatically wrapped into the `Validation` within the `ValidatedFutureIO` monad transformer.

This bare bone approach to sending / receiving messages is focussed on the idea that a higher-level API on top of Sentinel is responsible to make client usage more comfortable. 

# License
Copyright © 2013 Gideon de Kok

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
