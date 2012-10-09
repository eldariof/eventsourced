[![Build Status](https://secure.travis-ci.org/eligosource/eventsourced.png)](http://travis-ci.org/eligosource/eventsourced)

- This user guide is work in progress …
- Old user guide is [here](https://github.com/eligosource/eventsourced/blob/master/README.md)

Eventsourced
============

Introduction
------------

<i>Eventsourced</i> is a library that adds [event-sourcing](http://martinfowler.com/eaaDev/EventSourcing.html) to [Akka](http://akka.io/) actors. It appends event messages to a journal before they are processed by an actor and recovers actor state by replaying them. Appending event messages to a journal, instead of persisting actor state directly, allows for actor state persistence at very high transaction rates. Persisting changes instead of current state also serves as a foundation to automatically adjust actor state to cope with retroactive changes.

Events produced by an event-sourced actor are sent to destinations via one or more channels. Channels connect an actor to other application parts such as external web services, internal domain services, messaging systems, event archives or other (local or remote) event-sourced actors, to mention only a few examples. During recovery, channels ensure that events produced by an event-sourced actor are not redundantly delivered to destinations. They may also guarantee delivery of produced events by optionally appending them to a journal and removing them once they have been successfully delivered. 

Applications may connect event-sourced actors via channels to arbitrary complex event-sourced actor networks that can be consistently recovered by the library (e.g. after a crash or during normal application start). Channels play an important role during recovery as they ensure that replayed event messages do not wrongly interleave with new event messages created and sent by event-sourced actors. 

Based on these mechanisms, for example, the implementation of reliable, long-running business processes using event-sourced state machines becomes almost trivial. Here, applications may use Akka's [FSM](http://doc.akka.io/docs/akka/2.0.3/scala/fsm.html) (or just plain actors) to implement state machines where persistence and recovery is provided by the <i>Eventsourced</i> library.

The library itself is an [Akka etxension](http://doc.akka.io/docs/akka/2.0.3/scala/extending-akka.html) and provides [stackable traits](http://www.artima.com/scalazine/articles/stackable_trait_pattern.html) to add event-sourcing capabilities to actors. All message exchanges performed by the library are asynchronous and non-blocking. Message delivery semantics are <i>at-least-once</i> which essentially requires [idempotent](http://queue.acm.org/detail.cfm?id=2187821) event message receivers. The library provides means to make event message receivers idempotent based on message sequence numbers or sender message ids.

### Application

The library doesn't impose any restriction on the structure and semantics of application-level events. It uses the term <i>event</i> mainly to refer to application state changes. Consequently, applications may therefore use the library for command-sourcing as well. The [Eventsourced reference application](https://github.com/eligosource/eventsourced-example) even demonstrates how both approaches (i.e. event-sourcing and command-sourcing) can be combined into a single application.

It further demonstrates that the library fits well into applications that implement the [CQRS](http://martinfowler.com/bliki/CQRS.html) pattern and follow a [domain-driven design](http://domaindrivendesign.org/resources/what_is_ddd) (DDD). On the other hand, the library doesn't force applications to do so and allows them to implement event-sourcing (or command-sourcing) without CQRS and/or DDD.

### Journals

<i>Eventsourced</i> currently provides the following journal implementations.

- [LeveldbJournal](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.journal.LeveldbJournal$), a [LevelDB](http://code.google.com/p/leveldb/) and [leveldbjni](https://github.com/fusesource/leveldbjni) based journal which is currently recommended for application development and operation. It comes with two different optimizations which are further explained in the [API docs](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.journal.LeveldbJournal$) (see methods `processorStructured` and `sequenceStructured`). It will also be used in the following examples. Because LevelDB is a native library, this journal requires a special project configuration as explained in section [Installation](#installation). 
- [JournalioJournal](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.journal.JournalioJournal$), a [Journal.IO](https://github.com/sbtourist/Journal.IO) based journal. 
- [InmemJournal](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.journal.JournalioJournal$), an in-memory journal for testing purposes.

Further journal implementations are planned, including distributed, highly-available and horizontally scalable journals (based on [Apache BookKeeper](http://zookeeper.apache.org/bookkeeper/) or [Redis](http://redis.io/), for example). Also planned for the near future is a journal plugin API and an event archive.

### Resources

- [Eventsourced API](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.package)
- [Eventsourced reference application](https://github.com/eligosource/eventsourced-example) (work in progress ...)
- [Eventsourced forum](http://groups.google.com/group/eventsourced)


Installation
------------

See [Installation](https://github.com/eligosource/eventsourced/wiki/Installation) Wiki page.
