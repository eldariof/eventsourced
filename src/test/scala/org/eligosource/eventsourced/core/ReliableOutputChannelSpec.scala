/*
 * Copyright 2012 Eligotech BV.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eligosource.eventsourced.core

import java.io.File
import java.util.concurrent.{TimeUnit, LinkedBlockingQueue}

import akka.actor._
import akka.dispatch._
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout

import org.apache.commons.io.FileUtils

import org.scalatest.fixture._
import org.scalatest.matchers.MustMatchers

import org.eligosource.eventsourced.journal.LeveldbJournal

class ReliableOutputChannelSpec extends WordSpec with MustMatchers {
  import Channel._

  type FixtureParam = Fixture

  class Fixture {
    implicit val system = ActorSystem("test")
    implicit val timeout = Timeout(5 seconds)

    val destinationQueue = new LinkedBlockingQueue[Either[Message, Message]]
    val replyDestinationQueue = new LinkedBlockingQueue[Either[Message, Message]]

    val successDestination =
      system.actorOf(Props(new TestDesination(destinationQueue, None) with Responder))
    def failureDestination(failAtEvent: Any, enqueueFailures: Boolean, failureCount: Int) =
      system.actorOf(Props(new TestDesination(destinationQueue, Some(failAtEvent), enqueueFailures, failureCount) with Responder))

    val successReplyDestination =
      system.actorOf(Props(new TestDesination(replyDestinationQueue, None) with Responder))
    def failureReplyDestination(failAtEvent: Any, enqueueFailures: Boolean, failureCount: Int) =
      system.actorOf(Props(new TestDesination(replyDestinationQueue, Some(failAtEvent), enqueueFailures, failureCount) with Responder))

    val writeMsgListenerQueue = new LinkedBlockingQueue[WriteOutMsg]
    val writeMsgListener = system.actorOf(Props(new WriteMsgListener(writeMsgListenerQueue)))

    val journalDir = new File("target/journal")
    val journal = LeveldbJournal(journalDir)

    val channelConf = new ReliableChannelConf(10 milliseconds, 10 milliseconds, 3)
    val channel = system.actorOf(Props(new ReliableChannel(1, journal, channelConf)))

    def writeOutMsg(msg: Message) {
      Await.result(journal ? WriteOutMsg(1, msg, 1, SkipAck, system.deadLetters, false), timeout.duration)
    }

    def dequeue[A](queue: LinkedBlockingQueue[A], timeout: Long = 5000): A = {
      queue.poll(timeout, TimeUnit.MILLISECONDS)
    }

    def shutdown() {
      system.shutdown()
      system.awaitTermination(5 seconds)
      FileUtils.deleteDirectory(journalDir)
    }

    class TestDesination(
      // for interaction with test code
      blockingQueue: LinkedBlockingQueue[Either[Message, Message]],
      // event where first failure should occur
      failAtEvent: Option[Any],
      // if failing messages should be added to queue
      enqueueFailures: Boolean = false,
      // number of messages that will fail
      var failureCount: Int = 0) extends Actor { this: Responder =>

      def receive = {
        case event => {
          if (failAtEvent.map(_ == event).getOrElse(false) && failureCount > 0) {
            failureCount = failureCount - 1
            respond.withFailure(new Exception("test"))
            if (enqueueFailures) blockingQueue.put(Left(message))
          } else {
            blockingQueue.put(Right(message))
            respond.withEvent("re: %s" format event)
          }
        }
      }
    }

    class WriteMsgListener(blockingQueue: LinkedBlockingQueue[WriteOutMsg]) extends Actor {
      def receive = {
        case cmd: WriteOutMsg => blockingQueue.put(cmd)
      }
    }
  }

  def withFixture(test: OneArgTest) {
    val fixture = new Fixture
    try { test(fixture) } finally { fixture.shutdown() }
  }

  "A reliable output channel" when {
    "just created" must {
      "redeliver stored output messaged during recovery" in { fixture =>
        import fixture._

        writeOutMsg(Message("a", sequenceNr = 4L)) // sequence nr written to journal
        writeOutMsg(Message("b", sequenceNr = 5L)) // sequence nr written to journal

        channel ! SetDestination(successDestination)
        channel ! Deliver

        dequeue(destinationQueue) must be (Right(Message("a", sequenceNr = 4L)))
        dequeue(destinationQueue) must be (Right(Message("b", sequenceNr = 5L)))
      }
    }
    "delivering a single output message" must {
      "recover from destination failures" in { fixture =>
        import fixture._

        channel ! SetDestination(failureDestination("a", true, 2))
        channel ! SetReplyDestination(successReplyDestination)
        channel ! Deliver
        channel ! Message("a")
        channel ! Message("b")

        dequeue(destinationQueue) must be (Left(Message("a", sequenceNr = 1L)))
        dequeue(destinationQueue) must be (Left(Message("a", sequenceNr = 1L)))  // redelivery 1
        dequeue(destinationQueue) must be (Right(Message("a", sequenceNr = 1L))) // redelivery 2
        dequeue(destinationQueue) must be (Right(Message("b", sequenceNr = 2L)))

        dequeue(replyDestinationQueue) must be (Right(Message("re: a", sequenceNr = 1L)))
        dequeue(replyDestinationQueue) must be (Right(Message("re: b", sequenceNr = 2L)))
      }
      "recover from reply destination failures" in { fixture =>
        import fixture._

        channel ! SetDestination(successDestination)
        channel ! SetReplyDestination(failureReplyDestination("re: a", true, 2))
        channel ! Deliver
        channel ! Message("a")

        dequeue(destinationQueue) must be (Right(Message("a", sequenceNr = 1L)))
        dequeue(destinationQueue) must be (Right(Message("a", sequenceNr = 1L))) // redelivery 1
        dequeue(destinationQueue) must be (Right(Message("a", sequenceNr = 1L))) // redelivery 2

        dequeue(replyDestinationQueue) must be (Left(Message("re: a", sequenceNr = 1L)))
        dequeue(replyDestinationQueue) must be (Left(Message("re: a", sequenceNr = 1L)))  // redelivery 1
        dequeue(replyDestinationQueue) must be (Right(Message("re: a", sequenceNr = 1L))) // redelivery 2
      }
    }
    "delivering multiple output messages" must {
      "recover from destination failures and preserve message order" in { fixture =>
        import fixture._

        // number of failures generated by test destination
        val failures = 5

        // destination fails 3 times at event 5 and publishes failures
        channel ! SetDestination(failureDestination(4, true, failures))
        channel ! Deliver

        // send 7 messages to reliable output channel
        1 to 7 foreach { i => channel ! Message(i) }

        val expected = List(
          Right(Message(1, sequenceNr = 1L)), // success    at event 1
          Right(Message(2, sequenceNr = 2L)), // success    at event 2
          Right(Message(3, sequenceNr = 3L)), // success    at event 3
          Left( Message(4, sequenceNr = 4L)), // failure #1 at event 4
          Left( Message(4, sequenceNr = 4L)), // failure #2 at event 4, retry #1 before recovery
          Left( Message(4, sequenceNr = 4L)), // failure #3 at event 4, retry #2 before recovery
          Left( Message(4, sequenceNr = 4L)), // failure #4 at event 4, retry #3 before recovery
          Left( Message(4, sequenceNr = 4L)), // failure #5 at event 4, retry #1 after recovery #1
          Right(Message(4, sequenceNr = 4L)), // success    at event 4, retry #2 after recovery #1
          Right(Message(5, sequenceNr = 5L)), // success    at event 5
          Right(Message(6, sequenceNr = 6L)), // success    at event 6
          Right(Message(7, sequenceNr = 7L))  // success    at event 7
        )

        List.fill(12)(dequeue(destinationQueue)) must be(expected)

        // send another message to reliable output channel
        channel ! Message(8, sequenceNr = 0L)

        // check that sequence number is updated appropriately
        dequeue(destinationQueue) must be(Right(Message(8, sequenceNr = 8L)))
      }
    }
  }
  "A reliable output channel" must {
    "acknowledge messages by default" in { fixture =>
      import fixture._

      journal ! SetCommandListener(Some(writeMsgListener))

      channel ! Message("a", sequenceNr = 1)
      channel ! Deliver
      channel ! Message("b", sequenceNr = 2)

      dequeue(writeMsgListenerQueue).ackSequenceNr must be (1)
      dequeue(writeMsgListenerQueue).ackSequenceNr must be (2)
    }
    "not acknowledge messages on request" in { fixture =>
      import fixture._

      journal ! SetCommandListener(Some(writeMsgListener))

      channel ! Message("a", sequenceNr = 1, ack = false)
      channel ! Deliver
      channel ! Message("b", sequenceNr = 2, ack = false)
      channel ! Message("c", sequenceNr = 3)

      dequeue(writeMsgListenerQueue).ackSequenceNr must be (SkipAck)
      dequeue(writeMsgListenerQueue).ackSequenceNr must be (SkipAck)
      dequeue(writeMsgListenerQueue).ackSequenceNr must be (3)
    }
  }
}
