# Sentinel

![Sentinel](http://images.wikia.com/matrix/images/c/c2/Sentinel_Print.jpg)

## Overview

**Sentinel** is boilerplate for TCP based servers and clients through Akka IO (2.2).

The implementation focusses on raw performance, using pipelines through multiple sockets represented by multiple workers (both client / server side). Sentinel is designed for usage in persistent connection environments, making it (currently) less suited for things like HTTP and best suited for DB clients / RPC stacks.

In its current state, it's being used internally as a platform to test performance strategies for CPU and IO bound services. In the nearby future, Sentinel will fuel both [Raiku](http://github.com/gideondk/raiku) as other soon-to-be-released Akka based libraries.


## Status

Since the IO layer (and its API) in Akka 2.2 isn't stable yet, the current codebase of Sentinel can and will change heavily until the release of Akka 2.2.

In overall, treat Sentinel as pre-release alpha software (you've been warned ;-).

**Currently available in Sentinel:**

* Easy creation of reactive TCP servers / clients;
* Easy initialization of servers and clients for default or custom router worker strategies;
* Supervision (and restart / reconnection functionality) on both server and client for a defined number of workers;
* Default implementation for flow control;
* Sequencing and continuing multiple client operations using `Tasks`;
* Handling of read / write interests;
* Pluggable response handlers;
* Streaming Play Enumerator based pipeline.

The following is currently missing in Sentinel, but will be added soon:

* A far more solid test suite;
* Better error handling and recovery;
* Server to client communication;
* More examples, and overall awesomeness…

## Installation
You can install Sentinel through source (by publishing it into your local Ivy repository):

```bash
./sbt publish-local
```

Or by adding the repo:
<notextile><pre><code>"gideondk-repo" at "https://raw.github.com/gideondk/gideondk-mvn-repo/master"</code></pre></notextile>

to your SBT configuration and adding the `SNAPSHOT` to your library dependencies:

<notextile><pre><code>libraryDependencies ++= Seq(
  "nl.gideondk" %% "sentinel" % "0.5.1"
)
</code></pre></notextile>

## Usage
### Pipelines
The new pipeline implementation in Akka IO, focusses on the definition of pipes for both incoming as outgoing messages. In these pipelines, a definition is made how incoming or outgoing messages are parsed and formatted. 

Each of these *stages* can easily be composed into a bigger stage (`A => B >> B => C`) taking a the input of the first stage and outputting the format of the last stage. Within Sentinel, the eventual output send to the IO workers is in the standard `ByteString` format, making it nessecary that the end stage of the pipeline always outputs content of the `ByteString` type:

```scala
case class PingPongMessageFormat(s: String)

class PingPongMessageStage extends SymmetricPipelineStage[PipelineContext,
  PingPongMessageFormat, ByteString] {
  
    override def apply(ctx: PipelineContext) = new SymmetricPipePair[PingPongMessageFormat, ByteString] {
        implicit val byteOrder = ctx.byteOrder
        
        override val commandPipeline = { msg: PingPongMessageFormat ⇒
        	Seq(Right(ByteString(msg.s)))
        }
    
        override val eventPipeline = { bs: ByteString ⇒
        	Seq(Left(PingPongMessageFormat(new String(bs.toArray))))
        }
   }
}
```

### Client
After the definition of the pipeline, a client is easily created:

```scala
SentinelClient.randomRouting("localhost", 9999, 4, "Ping Client")(stages)
```

Defining the host and port where the client should connect to, the amount of workers used to handle commands / events, description of the client and the earlier defined context and stages (for the complete list of parameters, check the code for the moment). 
			
You can use the `randomRouting` / `roundRobinRouting` methods depending on the routing strategy you want to use to communicate to the workers. For a more custom approach the `apply` method is available, which lets you define a router strategy yourself. 

### Server
Handlers of requests are pluggable by defining a *requestHandler*. The request handler is defined using a function: `Init[WithinActorContext, Cmd, Evt] ⇒ ActorRef`, taking a `TcpPipelineHandler.Init` type and returning a new actor handling the `Init.Event` types from the TcpPipelineHandler and returning the appriopriate `Init.Command` types back to the TcpPipelienHandler

#### Async handler
By default a *async* handler is supplied with a easy to use interface. The async handler takes a `handle` function as argument, which it uses to handle incoming events from a client. The handle function is of type `Evt => Future[Cmd]`, taking the parsed result from the incoming pipe and preparing the response send back to the client. 

```scala
def handle(event: PingPongMessageFormat): Future[PingPongMessageFormat] = {
    event.s match {
          case "PING" ⇒ Future(PingPongMessageFormat("PONG"))
          case _      ⇒ Future.failed(new Exception("Unknown command"))
    }
}
```

The return type of `Cmd` should be wrapped into a `Future`, this makes it able to do other non-blocking work within, for instance, IO focused services. 

After the definition of the handler, the server can be defined in same fashion as the client: 

```scala
SentinelServer.async(9999, PingPongServerHandler.handle, "Ping Server")(stages)
```

### Client usage

Once a client and / or server has been set up, the `<~<` method can be used on the client to send a command to the connected server. Results are wrapped into a `Task` containing the type `Evt` defined in the incoming stage of the client.

```scala
PingPongTestHelper.pingClient <~< PingPongMessageFormat("PING")
res0: Task[PingPongMessageFormat]
```

`Task` combines a `Try`, `Future` and `IO` Monad into one type:  exceptions will be caught in the Try, all async actions are abstracted into a future monad and all IO actions are as pure as possible by using the Scalaz IO monad.

Use `run` to expose the Future, or use `start(d: Duration)` to perform IO and wait (blocking) on the future.

This bare bone approach to sending / receiving messages is focussed on the idea that a higher-level API on top of Sentinel is responsible to make client usage more comfortable. 

### Streamed requests / responses
#### Clients
It's possible to stream content towards Sentinel clients by using the the `|~>>>` or `|>>>` functions on a Play *Enumerator* (after importing `nl.sentinel.client._`) 

```scala
Enumerator(chunks) |~>>> client
res0: Task[TotalSize]

Enumerator(chunks) |>>> client
res1: Future[Try[TotalSize]]
```

The content within the *Enumerator* is folded to send each item to the TCP connection (returning in the `Evt` type, defined through the pipeline).

#### Streaming Pipelines
To handle incoming streams, a `EnumeratorStage` is available in Sentinel. Initialisation of `EnumeratorStage` takes two arguments: `terminator: Evt ⇒ Boolean` and `includeTerminator: Boolean = false`.

This first argument is a function taking each `Evt` and returning a boolean in case the streamed chunk can be treated as a `EOS`. The second argument is used to declare if the terminator should be included in the eventual stream of content.

A simple example can be found in the [Iteratee Spec](https://github.com/gideondk/sentinel/blob/master/src/test/scala/nl/gideondk/sentinel/IterateeSpec.scala).


# License
Copyright © 2013 Gideon de Kok

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
