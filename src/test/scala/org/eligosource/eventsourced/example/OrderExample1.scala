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

object OrderExample1 extends App {
  implicit val system = ActorSystem("example")
  implicit val timeout = Timeout(5 seconds)

  // create a journal
  val journal: ActorRef = LeveldbJournal(new File("target/example"))

  // create and initialize an event-sourcing Akka extension
  val extension = EventsourcingExtension(system, journal)

  // create a destination for output event messages
  val destination: ActorRef = system.actorOf(Props(new Destination))

  // create and register a channel
  val channel: ActorRef = extension.channelOf(DefaultChannelProps(1, destination))

  // create an event-sourced order processor
  val processor: ActorRef = extension.processorOf(ProcessorProps(1, new OrderProcessor(channel) with Eventsourced))

  // recover state from (previously) journaled events
  extension.recover()

  // send event message (fire-and-forget)
  processor ! Message(OrderSubmitted(Order("foo")))

  // send event message (and receive Ack reply)
  processor ? Message(OrderSubmitted(Order("bar"))) onSuccess {
    case Ack => println("input event message journaled")
  }

  // wait for output events to arrive (graceful shutdown coming soon)
  Thread.sleep(1000)

  // then shutdown
  system.shutdown()

  // event-sourced order processor
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

  // output message destination
  class Destination extends Actor {
    def receive = {
      case msg: Message => {
        println("received event %s" format msg.event)
        // acknowledge event message receipt to channel
        sender ! Ack
      }
    }
  }
}
