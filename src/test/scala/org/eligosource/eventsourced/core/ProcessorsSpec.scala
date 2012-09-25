package org.eligosource.eventsourced.core

import java.io.File
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import akka.actor._
import akka.util.duration._
import akka.util.Timeout

import org.apache.commons.io.FileUtils

import org.scalatest.fixture._
import org.scalatest.matchers.MustMatchers

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.LeveldbJournal

class ProcessorsSpec extends WordSpec with MustMatchers {
  type FixtureParam = Fixture

  class Fixture {
    implicit val system = ActorSystem("test")
    implicit val timeout = Timeout(5 seconds)

    val journalDir = new File("target/journal")
    val journal = LeveldbJournal(journalDir)

    val queue = new LinkedBlockingQueue[Any]
    val destination = system.actorOf(Props(new Destination(queue) with Emitter))

    val context = Context(journal)
      .addChannel("dest", destination)
      .addProcessor(2, new Changing with Eventsourced)
      .addProcessor(1, multicast(List(
        system.actorOf(Props(new Target with Emitter)),
        system.actorOf(Props(new Target with Emitter))
      ))).init()

    def dequeue(timeout: Long = 5000): Any = {
      queue.poll(timeout, TimeUnit.MILLISECONDS)
    }

    def shutdown() {
      system.shutdown()
      system.awaitTermination(5 seconds)
      FileUtils.deleteDirectory(journalDir)
    }
  }

  def withFixture(test: OneArgTest) {
    val fixture = new Fixture
    try {
      test(fixture)
    } finally {
      fixture.shutdown()
    }
  }

  class Changing extends Actor { this: Eventsourced =>
    val changed: Receive = {
      case "bar" => { emitter("dest").emitEvent("bar"); context.unbecome() }
    }

    def receive = {
      case "foo" => { emitter("dest").emitEvent("foo"); context.become(changed) }
      case "baz" => { emitter("dest").emitEvent("baz") }
    }

    override def unhandled(msg: Any) = msg match {
      case s: String => emitter("dest").emitEvent("unhandled")
      case _         => super.unhandled(msg)
    }
  }

  class Target extends Actor { this: Emitter =>
    def receive = {
      case "blah" => channels("dest") ! Message("blah", ack = false)
      case event  => emitter("dest").emitEvent(event)
    }
  }

  class Destination(queue: LinkedBlockingQueue[Any]) extends Actor { this: Receiver =>
    def receive = {
      case event => queue.put(event)
    }
  }

  "A multicast processor" must {
    "forward received event messages to its targets" in { fixture =>
      import fixture._

      context.processors(1) ! Message("test")

      dequeue() must be ("test")
      dequeue() must be ("test")
    }
    "forward received non-event messages to its targets" in { fixture =>
      import fixture._

      context.processors(1) ! "blah"

      dequeue() must be ("blah")
      dequeue() must be ("blah")
    }
  }
  "A receiver" must {
    "support behavior changes and overriding unhandled" in { fixture =>
      import fixture._

      context.processors(2) ! Message("foo")
      context.processors(2) ! Message("bar")
      context.processors(2) ! Message("baz")

      dequeue() must be ("foo")
      dequeue() must be ("bar")
      dequeue() must be ("baz")

      context.processors(2) ! Message("xyz")

      dequeue() must be ("unhandled")
    }
  }
}
