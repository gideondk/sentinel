# Sentinel

**Sentinel** is boilerplate for TCP based servers and clients through Using Akka IO and Akka Streams.

The framework's focus is to abstract away the nitty gritty parts of stream based communication to have a solution for reactive TCP communication with reasonable defaults. 
Sentinel is designed for usage in persistent connection environments, making it less suited for things like HTTP and best suited for database clients and persistent communication stacks stacks.

Sentinel brings a symmetrical design through *Processors*, resulting in the same request and response handling on both clients and servers. This not only makes it simple to share code on both sides, but also opens the possibility to inverse request & response flow from server to client.


## Status

The current codebase of Sentinel can change heavily over releases.
In overall, treat Sentinel as alpha software.

**Currently available in Sentinel:**

* Easy initialization of TCP clients, capable of handing normal request and response based flows as streaming requests and responses.
* Connection pooling and management and accompanied flow handling for clients.
* Reactive manner how handling available hosts / endpoints on clients.
* Basic server template using the same constructs / protocol as client.

**The following is currently missing in Sentinel, but will be added soon:**

* A far more solid test suite.
* Better error handling and recovery.
* Default functionality for callback based protocols.
* More solid server implementation, with possibility of direct server to client communication.

**(Currently) known issues:**

* There is no active (demand) buffering process within the client; when a stream is requested, but not consumed, additional requests on the same socket aren't demanded and therefore not pulled into new requests.


## Installation
You can install Sentinel through source (by publishing it into your local Ivy repository):

```bash
./sbt publish-local
```

Or by adding the repo:
<notextile><pre><code>"gideondk-repo" at "https://raw.github.com/gideondk/gideondk-mvn-repo/master"</code></pre></notextile>

to your SBT configuration and adding Sentinel to your library dependencies (currently only build against Scala 2.11):

<notextile><pre><code>libraryDependencies ++= Seq(
  "nl.gideondk" %% "sentinel" % "0.8-M1"
)
</code></pre></notextile>

## Architecture

The internal structure of Sentinel relies on the `Processor` BidiFlow. The Processor represents the connection between a client and a server and handles both the outgoing commands as incoming events through a `ProducerStage` and `ConsumerStage`.

Both clients as servers share the same `Processor`, which results in a symmetrical design for sending and receiving commands. When a message is received from the opposing host, a `Resolver` is used to determine the action or reaction on the received event. Based on the used protocol (which is defined as an additional `BidiFlow`, converting `ByteStrings` to `Events` and `Commands` to `ByteStrings`, a host can process the event and decide whether the consume the received event or to respond with new values (as in a normal request -> response way).

## Actions
The handle incoming events, multiple actions are defined which can be used to implement logic on top of the used protocol. Actions are split into consumer actions and producers actions, which make a antenna able to:

### Consumer Actions
`AcceptSignal`: Accept and consume a incoming signal and apply it on a pending registration

`AcceptError`: Accept a incoming error message and apply it as a failure on a pending registration

`ConsumeStreamChunk`: Accept a incoming stream chunk and consume add it to the current running stream

`EndStream`: Accept a incoming stream terminator and end the current ongoing stream

`ConsumeChunkAndEndStream`: Consumes the chunk and terminates the stream (combination of the two above)

### Producer Actions
`Signal`: Responds to the incoming signal with a new (async) signal

`CosumeStream`: Starts consuming the stream until a `EndStream` is received

`ProduceStream`: Produces a stream (Enumerator) for the requesting hosts

## Synchronicity
Normally, Sentinel clients connect to servers through multiple sockets to increase parallel performance on top of the synchronous nature of *TCP* sockets. 

Because of the *synchronous* nature of the underlying semantics, you have to handle each receiving signal in a appropriate way. Not handling all signals correctly could result in values ending up in incorrect order etc.


## Initialization
### Resolver
The default resolver for a client is one that automatically accepts all signals. This default behaviour makes it able to handle basic protocols asynchronously without defining a custom resolver on the client side.

It's easy to extend the behaviour on the client side for receiving stream responses by defining a custom `Resolver`:

```scala
trait DefaultSimpleMessageHandler extends Resolver[SimpleMessageFormat, SimpleMessageFormat] {
   def process(implicit mat: Materializer): PartialFunction[SimpleMessageFormat, Action] = {
    case SimpleStreamChunk(x)              ⇒ if (x.length > 0) ConsumerAction.ConsumeStreamChunk else ConsumerAction.EndStream
    case x: SimpleError                    ⇒ ConsumerAction.AcceptError
    case x: SimpleReply                    ⇒ ConsumerAction.AcceptSignal
    case SimpleCommand(PING_PONG, payload) ⇒ ProducerAction.Signal { x: SimpleCommand ⇒ Future(SimpleReply("PONG")) }
  }
}

object SimpleClientHandler extends DefaultSimpleMessageHandler
```

In a traditional structure, a different resolver should be used on the server side, handling incoming requests and responding with the appropriate response. The partial function taking a event and resulting in a action fully exposes the event to *route* the messages to the current action:

```scala
object SimpleServerHandler extends DefaultSimpleMessageHandler {

  def process(implicit mat: Materializer): PartialFunction[SimpleMessageFormat, Action] = {
    case SimpleStreamChunk(x)              ⇒ if (x.length > 0) ConsumerAction.ConsumeStreamChunk else ConsumerAction.EndStream
    case SimpleCommand(PING_PONG, payload) ⇒ ProducerAction.Signal { x: SimpleCommand ⇒ Future(SimpleReply("PONG")) }
    case SimpleCommand(TOTAL_CHUNK_SIZE, payload) ⇒ ProducerAction.ConsumeStream { x: Source[SimpleStreamChunk, Any] ⇒
      x.runWith(Sink.fold[Int, SimpleMessageFormat](0) { (b, a) ⇒ b + a.payload.length }).map(x ⇒ SimpleReply(x.toString))
    }
    case SimpleCommand(GENERATE_NUMBERS, payload) ⇒ ProducerAction.ProduceStream { x: SimpleCommand ⇒
      val count = payload.toInt
      Future(Source(List.range(0, count)).map(x ⇒ SimpleStreamChunk(x.toString)) ++ Source.single(SimpleStreamChunk("")))
    }
    case SimpleCommand(ECHO, payload) ⇒ ProducerAction.Signal { x: SimpleCommand ⇒ Future(SimpleReply(x.payload)) }
  }
}
```

Like illustrated, the `ProducerAction.Signal` producer action makes it able to respond with a Async response. Taking a function which handles the incoming event and producing a new value, wrapped in a `Future`.

`ProducerAction.ConsumeStream` takes a function handling the incoming `Source` with the consequential chunks, resulting in a new value wrapped in a `Future`

`ProducerAction.ProduceStream` takes a function handling the incoming event and returning a corresponding stream as a `Source` wrapped in a `Future`

### Client
After the definition of the pipeline, a client is easily created:

```scala
val client = Client(Source.single(ClientStage.HostUp(Host("localhost", port))), SimpleHandler, false, OverflowStrategy.backpressure, SimpleMessage.protocol)
```

The client takes a `Source[HostEvent, Any]]` as *hosts* parameter. Using this stream of either `HostUp` or `HostDown` events, the client updates its connection pool to a potentially changing set of endpoints.

The Client succeedingly takes the `Resolver` as parameter, a `shouldReact` parameter to configure the client if it should react to incoming events (for server to client communication), the to-be-used `OverflowStrategy` for incoming commands and the protocol `BidiFlow` to be used (`BidiFlow[Cmd, ByteString, ByteString, Evt, Any]`)


The client has a set of configurable settings:

```
nl.gideondk.sentinel {
  client {
    host {
      max-connections = 32
      max-failures = 16
      failure-recovery-duration = 4 seconds
      auto-reconnect = true
      reconnect-duration = 2 seconds
    }
    input-buffer-size = 1024
  }
}
```

`max-connections`: defines the amount of sockets to be opened per connected host.    

`max-failures`: defines the amount of (socket) failures a host may encounter before the host is removed from the connection pool.  

`failure-recovery-duration`: period after which the failure rate is resetted per connection.

`auto-reconnect`: when set, `HostDown` events from the client (after disconnect) are refeeded back as `HostUp` events into the client for reconnection purposes.  

`reconnect-duration`: the reconnection delay.  

`input-buffer-size`: The input buffer size of the client (before the configured `OverFlowStrategy` is used.

### Server
When the protocol and resolver are defined, creation of a server is very straight forward:

```scala
Server("localhost", port, SimpleServerHandler, SimpleMessage.protocol.reversed)
```

This will automatically start the server with the corresponding processor and handler, in the future, separate functionality for starting, restarting and stopping services will be available.

## Client usage

Once a client and / or server has been set up, the `ask` method can be used on the client to send a command to the connected server. Results are wrapped into a `Future` containing the type `Evt` defined in the incoming stage of the client.

```scala
client.ask(SimpleCommand(PING_PONG, "PING"))`
res0: Future[SimpleMessageFormat]
```

The bare bone approach to sending / receiving messages is focussed on the idea that a higher-level API on top of Sentinel is responsible to make client usage more comfortable.

### Streamed requests / responses
Sentinels structure for streaming requests and responses works best with protocols which somehow *pad* chunks and terminators. As the resolver has to be sure whether to consume a stream chunk and when to end the incoming stream, length based header structures are difficult to implement. Unstructured binary stream chunks can however be matched by protocol implementations if they are fundamentally different then other chunks, simply ignoring initial length headers and for instance breaking on *zero terminators* could be a way to implement *non-padded* stream chunks.

#### Sending
It's possible to stream content towards Sentinel clients by using the the `?<<-` command, expecting the command to be send to the server, accompanied by the actual stream:

```scala
val stream = Source.single(SimpleCommand(TOTAL_CHUNK_SIZE, "")) ++ Source(List.fill(1024)(SimpleStreamChunk("A"))) ++ Source.single(SimpleStreamChunk(""))

client.sendStream(stream)
res0: Future[SimpleMessageFormat]

```

The content within the *Source* is sent over the TCP connection (returning in the `Evt` type, defined through the pipeline).

#### Receiving
In the same manner, a stream can be requested from the server:

```scala
client.askStream(SimpleCommand(GENERATE_NUMBERS, "1024"))
res0: Future[Source[SimpleMessageFormat, Any]]
```

# Credits
The idea and internals for a large part of the client's connection pooling comes from [Maciej Ciołeks](https://github.com/maciekciolek) his wonderful [akka-http-lb](https://github.com/codeheroesdev/akka-http-lb) library.

# License
Copyright © 2017 Gideon de Kok

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
