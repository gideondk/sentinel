# Sentinel

![Sentinel](http://images.wikia.com/matrix/images/c/c2/Sentinel_Print.jpg)

## Overview

**Sentinel** is boilerplate for TCP based servers and clients through Akka IO (2.3).

The implementation focusses on raw performance, using pipelines through multiple sockets represented by multiple workers (both client / server side). Sentinel is designed for usage in persistent connection environments, making it (currently) less suited for things like HTTP and best suited for DB clients / RPC stacks.

Sentinel brings a unique symmetrical design through *Antennas*, resulting in the same request and response handling on both clients and servers. This not only makes it simple to share code on both sides, but also opens the possibility to inverse request & response flow from server to client.

In its current state, it's being used internally as a platform to test performance strategies for CPU and IO bound services. In the nearby future, Sentinel will fuel both [Raiku](http://github.com/gideondk/raiku) as other soon-to-be-released Akka based libraries.


## Status

The current codebase of Sentinel can change heavily over releases.
In overall, treat Sentinel as pre-release alpha software.

**Currently available in Sentinel:**

* Easy initialization of TCP servers and clients for default or custom router worker strategies;
* Supervision (and restart / reconnection functionality) on clients for a defined number of workers;
* Streaming requests and responses (currently) based on Play Iteratees;
* Direct server to client communication through symmetrical signal handling design.

The following is currently missing in Sentinel, but will be added soon:

* Replacement of `Iteratees` in favour of the upcoming *Akka Streams*;
* A far more solid test suite;
* Better error handling and recovery;
* Default functionality for callback based protocols;
* Streaming server to client communication.

## Installation
You can install Sentinel through source (by publishing it into your local Ivy repository):

```bash
./sbt publish-local
```

Or by adding the repo:
<notextile><pre><code>"gideondk-repo" at "https://raw.github.com/gideondk/gideondk-mvn-repo/master"</code></pre></notextile>

to your SBT configuration and adding Sentinel to your library dependencies (currently only build against Scala 2.11):

<notextile><pre><code>libraryDependencies ++= Seq(
  "nl.gideondk" %% "sentinel" % "0.7.5.1"
)
</code></pre></notextile>

## Architecture

The internal structure of Sentinel relies on a *Antenna* actor. The Antenna represents the connection between a client and a server and handles both the outgoing commands as incoming replies and handles the events received from the underlying *TCP* actors.

Within the antenna structure, two child actors are defined. One used for consuming replies from the connected host and one for the production of values for the connected host.

Both clients as servers share the same antenna construction, which results in a symmetrical design for sending and receiving commands. When a message is received from the opposing host, a *resolver* is used to determine the action or reaction on the received event. Based on the used protocol (as defined in the underlying protocol pipeline), a host can process the event and decide whether the consume the received event or to respond with new values (as in a normal request -> response way).

Once, for instance, a command is sent to a client (for a response from the connected server), the payload is sent to the opposing host and a reply-registration is set within the consumer part of the antenna. This registration and accompanying promise is completed with the consequential response from the server.

## Actions
The handle incoming events, multiple actions are defined which can be used to implement logic on top of the used protocol. Actions are split into consumer actions and producers actions, which make a antenna able to:

### Consumer Actions
`AcceptSignal`: Accept and consume a incoming signal and apply it on a pending registration

`AcceptError`: Accept a incoming error message and apply it as a failure on a pending registration

`ConsumeStreamChunk`: Accept a incoming stream chunk and consume add it to the current running stream

`EndStream`: Accept a incoming stream terminator and end the current ongoing stream

`ConsumeChunkAndEndStream`: Consumes the chunk and terminates the stream (combination of the two above)

`Ignore`: Ignores the current received signal

### Producer Actions
`Signal`: Responds to the incoming signal with a new (async) signal

`CosumeStream`: Starts consuming the stream until a `EndStream` is received

`ProduceStream`: Produces a stream (Enumerator) for the requesting hosts

## Synchronicity
Normally, Sentinel clients connect to servers through multiple sockets to increase parallel performance on top of the synchronous nature of *TCP* sockets. Producers and consumers implement a state machine to correctly respond to running incoming and outgoing streams, handling messages which don't impose treats to the message flow and stashing messages which could leak into the running streams.

Because of the synchronous nature of the underlying semantics, you have to handle each receiving signal in a appropriate way. Not handling all signals correctly could result in values ending up in incorrect registrations etc.


## Initialization
### Pipelines
The Pipeline implementation available in Akka 2.2 is becoming obsolete in Akka 2.3 to be replaced with a (better) alternative later on in Akka 2.4. As it seemed that pipelines aren't the best solution for Akka, this currently leaves Akka 2.3 without a reactive *protocol layer*. To bridge the period until a definite solution is available, the "older" pipeline implementation is packaged along with Sentinel.

The pipeline implementation focusses on the definition of pipes for both incoming as outgoing messages. In these pipelines, a definition is made how incoming or outgoing messages are parsed and formatted.

Each of these *stages* can easily be composed into a bigger stage (`A => B >> B => C`) taking a the input of the first stage and outputting the format of the last stage. Within Sentinel, the eventual output send to the IO workers is in the standard `ByteString` format, making it necessary that the end stage of the pipeline always outputs content of the `ByteString` type:

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

### Resolver
The default resolver for a client is one that automatically accepts all signals. This default behaviour makes it able to handle basic protocols asynchronously without defining a custom resolver on the client side.

It's easy to extend the behaviour on the client side for receiving stream responses by defining a custom `Resolver`:

```scala
import SimpleMessage._
trait DefaultSimpleMessageHandler extends Resolver[SimpleMessageFormat, SimpleMessageFormat] {
  def process = {
    case SimpleStreamChunk(x) ⇒ if (x.length > 0) ConsumerAction.ConsumeStreamChunk else ConsumerAction.EndStream

    case x: SimpleError       ⇒ ConsumerAction.AcceptError
    case x: SimpleReply       ⇒ ConsumerAction.AcceptSignal
  }
}

object SimpleClientHandler extends DefaultSimpleMessageHandler
```

In a traditional structure, a different resolver should be used on the server side, handling incoming requests and responding with the appropriate response. The partial function taking a event and resulting in a action fully exposes the event to *route* the messages to the current action:

```scala
object SimpleServerHandler extends DefaultSimpleMessageHandler {

  override def process = super.process orElse {
    case SimpleCommand(PING_PONG, payload) ⇒ ProducerAction.Signal { x: SimpleCommand ⇒ Future(SimpleReply("PONG")) }

    case SimpleCommand(TOTAL_CHUNK_SIZE, payload) ⇒ ProducerAction.ConsumeStream { x: SimpleCommand ⇒
      s: Enumerator[SimpleStreamChunk] ⇒
        s |>>> Iteratee.fold(0) { (b, a) ⇒ b + a.payload.length } map (x ⇒ SimpleReply(x.toString))
    }

    case SimpleCommand(GENERATE_NUMBERS, payload) ⇒ ProducerAction.ProduceStream { x: SimpleCommand ⇒
      val count = payload.toInt
      Future((Enumerator(List.range(0, count): _*) &> Enumeratee.map(x ⇒ SimpleStreamChunk(x.toString))) >>> Enumerator(SimpleStreamChunk("")))
    }

    case SimpleCommand(ECHO, payload) ⇒ ProducerAction.Signal { x: SimpleCommand ⇒ Future(SimpleReply(x.payload)) }
  }
}
```

Like illustrated, the `ProducerAction.Signal` producer action makes it able to respond with a Async response. Taking a function which handles the incoming event and producing a new value, wrapped in a `Future`.

`ProducerAction.ConsumeStream` takes a function handling the incoming event and the Enumerator with the consequential chunks, resulting in a new value wrapped in a `Future`

`ProducerAction.ProduceStream` takes a function handling the incoming event and returning a corresponding stream as a `Enumerator` wrapped in a `Future`

### Client
After the definition of the pipeline, a client is easily created:

```scala
Client.randomRouting("localhost", 9999, 4, "Ping Client", stages = stages, resolver = resolver)
```

Defining the host and port where the client should connect to, the amount of workers used to handle commands / events, description of the client and the earlier defined context, stages and resolver (for the complete list of parameters, check the code for the moment).

You can use the `randomRouting` / `roundRobinRouting` methods depending on the routing strategy you want to use to communicate to the workers. For a more custom approach the `apply` method is available, which lets you define a router strategy yourself.

### Server
When the stages and resolver are defined, creation of a server is very straight forward:

```scala
Server(portNumber, SimpleServerHandler, "Server", SimpleMessage.stages)
```

This will automatically start the server with the corresponding stages and handler, in the future, separate functionality for starting, restarting and stopping services will be available.

## Client usage

Once a client and / or server has been set up, the `?` method can be used on the client to send a command to the connected server. Results are wrapped into a `Future` containing the type `Evt` defined in the incoming stage of the client.

```scala
PingPongTestHelper.pingClient ? PingPongMessageFormat("PING")
res0: Future[PingPongMessageFormat]
```

The bare bone approach to sending / receiving messages is focussed on the idea that a higher-level API on top of Sentinel is responsible to make client usage more comfortable.

### Streamed requests / responses
Sentinels structure for streaming requests and responses works best with protocols which somehow *pad* chunks and terminators. As the resolver has to be sure whether to consume a stream chunk and when to end the incoming stream, length based header structures are difficult to implement. Unstructured binary stream chunks can however be matched by protocol implementations if they are fundamentally different then other chunks, simply ignoring initial length headers and for instance breaking on *zero terminators* could be a way to implement *non-padded* stream chunks.

#### Sending
It's possible to stream content towards Sentinel clients by using the the `?<<-` command, expecting the command to be send to the server, accompanied by the actual stream:

```scala
c ?<<- (SimpleCommand(TOTAL_CHUNK_SIZE, ""), Enumerator(chunks: _*))
res0: Future[SimpleCommand]

c ?<<- Enumerator((SimpleCommand(TOTAL_CHUNK_SIZE, "") ++ chunks): _*)
res1: Future[SimpleCommand]

```

The content within the *Enumerator* is folded to send each item to the TCP connection (returning in the `Evt` type, defined through the pipeline).

#### Receiving
In the same manner, a stream can be requested from the server:

```scala
c ?->> SimpleCommand(GENERATE_NUMBERS, count.toString)
res0: Future[Enumerator[SimpleCommand]]
```

## Server usage
Although functionality will be expanded in the future, it's currently also possible to send requests from the server to the connected clients. This can be used for retrieval of client information on servers request, but could also be used as a retrieval pattern where clients are dormant after request, but respond to requests when necessary (retrieving sensor info per example).

The following commands can be used to retrieve information:

`?`: Sends command to *one* (randomly chosen) connected socket for a answer, resulting in one event.

`?*`: Sends a command to all connected hosts, resulting in a list of events from each host individually.

`?**`: Sends a command to all connected sockets, resulting in a list of events from all connected sockets.

Simple server metrics are available through the `connectedSockets` and `connectedHosts` commands, returning a `Future[Int]` containing the corresponding count.

# License
Copyright © 2014 Gideon de Kok

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.


[![Bitdeli Badge](https://d2weczhvl823v0.cloudfront.net/gideondk/sentinel/trend.png)](https://bitdeli.com/free "Bitdeli Badge")
