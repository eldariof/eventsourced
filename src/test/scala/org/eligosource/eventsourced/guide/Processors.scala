package org.eligosource.eventsourced.guide

import java.io.File

import akka.actor._
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.LeveldbJournal

object Processors extends App {
  implicit val system = ActorSystem("example")
  implicit val timeout = Timeout(5 seconds)

  // create a journal
  val journal: ActorRef = LeveldbJournal(new File("target/example-2"))

  // create an event-sourcing extension
  val extension = EventsourcingExtension(system, journal)

  // event-sourced processor definition
  class Processor extends Actor { this: Receiver =>
    def receive = {
      case "foo" => println("received event foo (sequence number = %d)" format message.sequenceNr)
    }
  }

  // create and register event-sourced processor
  val processor: ActorRef = extension.processorOf(ProcessorProps(1, new Processor with Receiver with Eventsourced))

  // recover registered processors by replaying journaled events
  extension.recover()

  // send event message to processor
  processor ! Message("foo")

  // wait for all messages to arrive (graceful shutdown coming soon)
  Thread.sleep(1000)

  // then shutdown
  system.shutdown()
}
