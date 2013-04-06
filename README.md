# Sentinel

![Sentinel](http://images.wikia.com/matrix/images/c/c2/Sentinel_Print.jpg)

## Overview

**Sentinel** is boilerplate for TCP based servers and clients through Akka IO (2.2).

The implementation focusses on raw performance, using pipelines through multiple sockets represented by multiple workers (both client / server side). Sentinel is designed for usage in persistent connection environments, making it (currently) less suited for things like HTTP and best suited for DB clients / RPC stacks.

In its current state, it's being used internally as a platform to test performance strategies for CPU and IO bound services. In the nearby future, Sentinel will fuel both [Raiku](http://github.com/gideondk/raiku) as other soon-to-be-released Akka based libraries.


## Status

Since the IO layer (and its API) in Akka 2.2 isn't stable yet, the current codebase of Sentinel can and will change heavily until the release of Akka 2.2.

In overall, treat Sentinel as pre-release alpha software.

**Currently available in Sentinel:**

* Easy creation of reactive TCP servers / clients;
* Easy initialization of servers and clients for default or custom router worker strategies;
* Supervision (and restart / reconnection functionality) on both server and client for a defined number of workers;
* Default implementations for both Ack as Noack based flow control;
* Sequencing and continuing multiple client operations using monad transformers (ValidatedFuture, ValidatedFutureIO).

The following is currently missing in Sentinel, but will be added soon:

* Handling of read interests;
* More robust benchmarks for CPU / IO bound services to test router / worker strategies;
* Better error handling and recovery;
* Server to client communication;
* More examples, and overall awesomeness…

## Installation
You can install Sentinel through source (by publishing it into your local Ivy repository):

	./sbt publish-local
	
(Repository will follow soon…)

## Usage
### Pipelines
The new pipeline implementation in Akka IO, focusses on the definition of pipes for both incoming as outgoing messages. In these pipelines, a definition is made how incoming or outgoing messages are parsed and formatted. 

Each of these *stages* can easily be composed into a bigger stage (`A => B >> B => C`) taking a the input of the first stage and outputting the format of the last stage. Within Sentinel, the eventual output send to the IO workers is in the standard `ByteString` format, making it nessecary that the end stage of the pipeline always outputs content of the `ByteString` type:

<pre><code>case class PingPongMessageFormat(s: String)

class PingPongMessageStage extends SymmetricPipelineStage[HasByteOrder,
  PingPongMessageFormat, ByteString] {
  
    override def apply(ctx: HasByteOrder) = new SymmetricPipePair[PingPongMessageFormat, ByteString] {
        implicit val byteOrder = ctx.byteOrder
        
        override val commandPipeline = { msg: PingPongMessageFormat ⇒
        	Seq(Right(ByteString(msg.s)))
        }
    
        override val eventPipeline = { bs: ByteString ⇒
        	Seq(Left(PingPongMessageFormat(new String(bs.toArray))))
        }
   }
}
</code></pre>

It's possible to share a context between each stage of the pipeline, this context must only be used once within one pipeline. Sharing this context between multiple pipelines will result in unpredicted behavior, so it's best to create this context by using a generating function: 

<pre><code>def ctx = new HasByteOrder {
  def byteOrder = java.nio.ByteOrder.BIG_ENDIAN
}
</code></pre>


### Client
After the definition of the pipeline, a client is easily created:

	SentinelClient.randomRouting("localhost", 9999, 4, "Ping Client")(ctx, stages, false)

Defining the host and port where the client should connect to, the amount of workers used to handle commands / events, description of the client and the earlier defined context and stages (for the complete list of parameters, check the code for the moment). 
			
You can use the `randomRouting` / `roundRobinRouting` methods depending on the routing strategy you want to use to communicate to the workers. For a more custom approach the `apply` method is available, which lets you define a router strategy yourself. 

### Server
The server follow practically the same route as the client, with one big difference: a handler must be defined to handle the incoming events from a client. The handle function is of type `Evt => Cmd`, taking the parsed result from the incoming pipe and preparing the response send back to the client. 

	def handle(event: PingPongMessageFormat): Future[PingPongMessageFormat] = {
    	event.s match {
      	    case "PING" ⇒ Future(PingPongMessageFormat("PONG"))
      	    case _      ⇒ Future.failed(new Exception("Unknown command"))
    	}
  	}

The return type of `Cmd` should be wrapped into a `Future`, this makes it able to do other non-blocking work within, for instance, IO focused services. Since you probably build your own handler on top of the `handle` function, Sentinel doesn't implement `Response` / `AsyncReponse` and leaves the implemention to the developer.

After the definition of the handler, the server can be defined in same fashion as the client: 

	SentinelServer.randomRouting(9999, 16, PingPongServerHandler.handle, "Ping Server")(ctx, stages, false)


### Ack vs Noack
Sentinel implements both Ack as Noack based flow-control. Ack based flow-control is implemented through a queue, dequeuing the next command when the underlying TCP actor has successfully send the previous command. 

Noack based flow control should give better performance in most cases, since it only resends failed messages, but isn't suited in environments where the order of commands is important (since a failed command A can be resend later then the successful B and C commands). 

### Client usage

Once a client and / or server has been set up, the `<~<` method can be used on the client to send a command to the connected server. Results are wrapped into a `ValidatedFutureIO` Monad transformer containing the type `Evt` defined in the incoming stage of the client.

	PingPongTestHelper.pingClient <~< PingPongMessageFormat("PING")
	res0: ValidatedFutureIO[PingPongMessageFormat]

`ValidatedFutureIO` combines a `Validation`, `Future` and `IO` Monad into one type:  exceptions will be caught in the Validation, all async actions are abstracted into a future monad and all IO actions are as pure as possible by using the Scalaz IO monad.

Use `unsafePerformIO` to expose the Future, or use `unsafeFulFill(d: Duration)` to perform IO and wait (blocking) on the future.

This bare bone approach to sending / receiving messages is focussed on the idea that a higher-level API on top of Sentinel is responsible to make client usage more comfortable. 

# License
Copyright © 2013 Gideon de Kok

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
