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
package org.eligosource.eventsourced.example

import java.io.File

import akka.actor._
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.LeveldbJournal

object BasicExample extends App {
  implicit val system = ActorSystem("example")
  implicit val timeout = Timeout(5 seconds)

  // Event sourcing extension
  implicit val extension = EventsourcingExtension(system, LeveldbJournal(new File("target/example")))

  // Register event-sourced processors
  val processorA = extension.processorOf(ProcessorProps(1, new ProcessorA with Receiver with Eventsourced))
  val processorB = extension.processorOf(ProcessorProps(2, new ProcessorB with Emitter with Eventsourced))

  // Modification 'with Receiver' makes actor an acknowledging event/command message receiver
  val destination = system.actorOf(Props(new Destination with Receiver))

  // Configure and register channels
  val channelA = extension.channelOf(DefaultChannelProps(1, processorA).withName("channelA"))
  val channelB = extension.channelOf(ReliableChannelProps(2, destination).withName("channelB"))

  // Register another event-sourced processors (which isn't modified with Receiver or Emitter)
  val processorC = extension.processorOf(ProcessorProps(3, new ProcessorC(channelA, channelB) with Eventsourced))

  // recover all registered processors
  extension.recover()

  val p: ActorRef = processorC // can be replaced with processorB

  // send event message to p
  p ! Message("some event")

  // send event message to p and receive response (Ack) once the event has been persisted
  // (event message is persisted before the processor receives it)
  p ? Message("some event") onSuccess { case Ack => println("event written to journal") }

  // send event message to p but receive application-level response from processor (or any of its destinations)
  // (event message is persisted before receiving the response)
  p ?? Message("some event") onSuccess { case resp => println("received response %s" format resp) }

  // send message to p (bypasses journaling because it's not an instance of Message)
  p ! "blah"

  // -----------------------------------------------------------
  //  Actor definitions
  // -----------------------------------------------------------

  class ProcessorA extends Actor {
    def receive = {
      case event => {
        // do something with event
        println("received event = %s" format event)
      }
    }
  }

  class ProcessorB extends Actor { this: Emitter with Eventsourced =>
    def receive = {
      case "blah" => {
        println("received non-journaled message")
      }
      case event => {
        // Receiver actors have access to:
        val msg = message          // current message
        val snr = sequenceNr       // sequence number of message
        val sid = senderMessageId  // sender message id of current message (for duplicate detection)
        val ini = initiator        // initial sender of current message (usually different from current 'sender')
        // ...

        // Eventsourced actors have access to processor id
        val pid = id

        // do something with event
        println("received event = %s (processor id = %d, sequence nr = %d)" format(event, pid, snr))

        // Emitter actors can emit events to named channels
        emitter("channelA").emitEvent("out-a")
        emitter("channelB").emitEvent("out-b")

        // optionally respond to initial sender (initiator)
        initiator ! "done"
      }
    }
  }

  // does the same as ProcessorB but doesn't use any attributes of traits Emitter and Eventsourced
  class ProcessorC(channelA: ActorRef, channelB: ActorRef) extends Actor {
    def receive = {
      case "blah" => {
        println("received non-journaled message")
      }
      case msg: Message => {
        println("received event = %s (processor id = 3, sequence nr = %d)" format(msg.event, msg.sequenceNr))

        channelA ! msg.copy(event = "out-a")
        channelB ! msg.copy(event = "out-b")

        msg.sender.foreach(_ ! "done")
      }
    }
  }

  class Destination extends Actor { this: Receiver =>
    def receive = {
      case event => {
        // Receiver actors have access to:
        val msg = message          // current message
        val snr = sequenceNr       // sequence number of message
        val sid = senderMessageId  // sender message id of current message (for duplicate detection)
        val ini = initiator        // initial sender of current message (usually different from current 'sender')
        // ...

        // do something with event
        println("received event = %s" format event)
      }
    }
  }
}
