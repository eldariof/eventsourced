[![Build Status](https://secure.travis-ci.org/eligosource/eventsourced.png)](http://travis-ci.org/eligosource/eventsourced)

- This user guide is work in progress …
- Old user guide is [here](https://github.com/eligosource/eventsourced/blob/master/README.md)

Eventsourced
============

Introduction
------------

<i>Eventsourced</i> is a library that adds [event-sourcing](http://martinfowler.com/eaaDev/EventSourcing.html) to [Akka](http://akka.io/) actors. It appends event messages to a journal before they are processed by an actor and recovers actor state by replaying them. Appending event messages to a journal, instead of persisting actor state directly, allows for actor state persistence at very high transaction rates. Persisting changes instead of current state also serves as a foundation to automatically adjust actor state to cope with retroactive changes.

Events produced by an event-sourced actor are sent to destinations via one or more channels. Channels connect an event-sourced actor to other application parts such as external web services, internal domain services, messaging systems, event archives or other (local or remote) event-sourced actors, to mention only a few examples. During recovery, channels ensure that events produced by an event-sourced actor are not redundantly delivered to destinations. They may also guarantee delivery of produced events by optionally appending them to a journal and removing them once they have been successfully delivered. 

Applications may connect event-sourced actors via channels to arbitrary complex event-sourced actor networks that can be consistently recovered by the library (e.g. after a crash or during normal application start). Channels play an important role during recovery as they ensure that replayed event messages do not wrongly interleave with new event messages created and sent by event-sourced actors. 

Based on these mechanisms, for example, the implementation of reliable, long-running business processes using event-sourced state machines becomes almost trivial. Here, applications may use Akka's [FSM](http://doc.akka.io/docs/akka/2.0.3/scala/fsm.html) (or just plain actors) to implement state machines where persistence and recovery is provided by the <i>Eventsourced</i> library.

The library itself is an [Akka etxension](http://doc.akka.io/docs/akka/2.0.3/scala/extending-akka.html) and provides [stackable traits](http://www.artima.com/scalazine/articles/stackable_trait_pattern.html) to add event-sourcing capabilities to actors. All message exchanges performed by the library are asynchronous and non-blocking. Message delivery semantics are <i>at-least-once</i> which essentially requires [idempotent](http://queue.acm.org/detail.cfm?id=2187821) event message receivers. The library provides means to make event message receivers idempotent based on message sequence numbers or sender message ids.

### Application

The library doesn't impose any restriction on the structure and semantics of application-level events. It uses the term <i>event</i> mainly to refer to application state changes. Consequently, applications may therefore use the library for command-sourcing as well. The [Eventsourced reference application](https://github.com/eligosource/eventsourced-example) even demonstrates how both approaches (i.e. event-sourcing and command-sourcing) can be combined into a single application.

It further demonstrates that the library fits well into applications that implement the [CQRS](http://martinfowler.com/bliki/CQRS.html) pattern and follow a [domain-driven design](http://domaindrivendesign.org/resources/what_is_ddd) (DDD). On the other hand, the library doesn't force applications to do so and allows them to implement event-sourcing (or command-sourcing) without CQRS and/or DDD.

### Journals

For persisting event messages, <i>Eventsourced</i> currently provides the following journal implementations:

- [`LeveldbJournal`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.journal.LeveldbJournal$), a [LevelDB](http://code.google.com/p/leveldb/) and [leveldbjni](https://github.com/fusesource/leveldbjni) based journal which is currently recommended for application development and operation. It comes with two different optimizations which are further explained in the [API docs](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.journal.LeveldbJournal$) (see methods `processorStructured` and `sequenceStructured`). It will also be used in the following examples. Because LevelDB is a native library, this journal requires a special project configuration as explained in section [Installation](#installation). 
- [`JournalioJournal`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.journal.JournalioJournal$), a [Journal.IO](https://github.com/sbtourist/Journal.IO) based journal. 
- [`InmemJournal`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.journal.JournalioJournal$), an in-memory journal for testing purposes.

Further journal implementations are planned, including distributed, highly-available and horizontally scalable journals (based on [Apache BookKeeper](http://zookeeper.apache.org/bookkeeper/) or [Redis](http://redis.io/), for example). Also planned for the near future is a journal plugin API and an event archive.

### Resources

- [Eventsourced API](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.package)
- [Eventsourced reference application](https://github.com/eligosource/eventsourced-example) (work in progress ...)
- [Eventsourced forum](http://groups.google.com/group/eventsourced)


Installation
------------

See [Installation](https://github.com/eligosource/eventsourced/wiki/Installation) Wiki page.

First steps
-----------

Let's start with a simple example that demonstrates some basic library usage. An event-sourced actor (`OrderProcessor`) consumes `OrderSubmitted` events, stores the submitted orders in memory and produces ("emits") `OrderAccepted` events to a `Destination` via a channel. This is summarized in the following figure (legend is in [Appendix A](#appendix-a-legend)):

![Order Example 1](https://raw.github.com/eligosource/eventsourced/wip-es-trait/doc/images/order-example-1.png)

Events are always transported with an event [`Message`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.Message). Event messages sent to `OrderProcessor` are written to a journal before they are received by the `OrderProcessor`. The state of the `OrderProcessor` can therefore be recovered by *replaying* these messages. During a replay, the `OrderProcessor` still emits `OrderAccepted` events to the channel but the channel will only deliver those events to `Destination` that have not been successfully delivered yet. The channel is able to distinguish between successfully delivered events and not yet delivered events because it logs successful deliveries to the journal.

The following subsections present two different approaches for implementing the above example, a [low-level approach](#low-level-approach) and a [higher-level approach](#higher-level-approach). The low-level approach makes it more explicit how the different components of an event-sourced application interact, the higher-level approach shows how to reduce verbosity by using additional library features that support common use cases. 

Both approaches need to create and initialize an [`EventsourcingExtension`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.EventsourcingExtension) with an actor `system` and a `journal`

    import java.io.File
    import akka.actor._

    import org.eligosource.eventsourced.core._
    import org.eligosource.eventsourced.journal.LeveldbJournal

    implicit val system = ActorSystem("example")

    // create a journal
    val journal: ActorRef = LeveldbJournal(new File("target/example"))

    // create and initialize an event-sourcing Akka extension
    val extension = EventsourcingExtension(system, journal)

and share the definition of domain events and domain objects:

    // domain events
    case class OrderSubmitted(order: Order)
    case class OrderAccepted(order: Order)

    // domain object
    case class Order(id: Int, details: String, validated: Boolean, creditCardNumber: String)

    object Order {
      def apply(details: String): Order = apply(details, "")
      def apply(details: String, creditCardNumber: String): Order = new Order(-1, details, false, creditCardNumber)
    }

### Low-level approach

Code from this section is part of the project's test sources ([`OrderExample1`](https://github.com/eligosource/eventsourced/blob/wip-es-trait/src/test/scala/org/eligosource/eventsourced/example/OrderExample1.scala)) and can be executed with `sbt 'test:run-nobootcp org.eligosource.eventsourced.example.OrderExample1'` (see [here](eventsourced/wiki/Installation) for details about the `run-nobootcp` task).

In this (rather verbose) approach, the `OrderProcessor` and `Destination` deal with event [`Message`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.Message)s directly. Furthermore, the channel is passed to the `OrderProcessor` constructor as `ActorRef`:


    // event
    class OrderProcessor(channel: ActorRef) extends Actor {
      var orders = Map.empty[Int, Order] // processor state
  
      def receive = {
        case msg: Message => msg.event match {
          case OrderSubmitted(order) => {
            val id = orders.size          // generate next id (= number of existing orders)
            val upd = order.copy(id = id) // update submitted order with generated id
            orders = orders + (id -> upd) // add submitted order to map of existing orders
  
            // emit new event message containing the updated order
            channel ! msg.copy(event = OrderAccepted(upd))
          }
        }
      }
    }

When receiving an event message, the actor extracts the submitted `order` from the `OrderSubmitted` event, updates the `order` with a generated `id` and emits an `OrderAccepted` event, containing the updated order, to `channel`. The emitted event is contained in an event message that is derived from the received `msg`.

One important thing to note is that the `OrderProcessor` is a plain actor that doesn't need to care about writing event messages to a journal. Event-sourcing functionality (including journaling, recovery, …) is added later by modifying `OrderProcessor` with the stackable [`Eventsourced`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.Eventsourced) trait during instantiation (see later). 

When `OrderProcessor` emits an event message to `channel`, it is delivered to the channel's destination. Channel destinations are actors as well. They must acknowledge the receipt of an event message by replying with an [`Ack`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.package$$Ack$), as done in our example:

    class Destination extends Actor {
      def receive = {
        case msg: Message => {
          println("received event %s" format msg.event)
          // acknowledge event message receipt to channel
          sender ! Ack
        }
      }
    }

When the channel receives an `Ack` reply, it logs the successful delivery of that message to the journal. 

… 

### Higher-level approach

… 

Appendix A: Legend
------------------

![Legend](https://raw.github.com/eligosource/eventsourced/wip-es-trait/doc/images/legend.png)
