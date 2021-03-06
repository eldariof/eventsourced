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

import akka.actor._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.Decorator.Emit

import FsmExample._

class FsmExample extends EventsourcingSpec[Fixture] {
  "A decorated FSM actor" must {
    "recover its state from stored event messages" in { fixture =>
      import fixture._

      val door = configure()
      extension.recover()

      door ! Message("open")
      door ! Message("close")
      door ! Message("close")

      dequeue() must be (DoorMoved(1))
      dequeue() must be (DoorMoved(2))
      dequeue() must be (DoorNotMoved("cannot close door in state Closed"))

      val recoveredDoor = configure()
      extension.recover()

      recoveredDoor ! Message("open")
      recoveredDoor ! Message("close")

      dequeue() must be (DoorMoved(3))
      dequeue() must be (DoorMoved(4))
    }
  }
}

object FsmExample {
  class Fixture extends EventsourcingFixture[Any] {
    val destination = system.actorOf(Props(new Destination(queue) with Receiver with Idempotent))

    def configure(): ActorRef = {
      extension.channelOf(DefaultChannelProps(1, destination).withName("dest"))
      extension.processorOf(ProcessorProps(1, decorator(system.actorOf(Props(new Door)))))
    }
  }

  sealed trait DoorState

  case object Open extends DoorState
  case object Closed extends DoorState

  case class DoorMoved(times: Int)
  case class DoorNotMoved(reason: String)

  class Door extends Actor with FSM[DoorState, Int] {
    startWith(Closed, 0)

    when(Closed) {
      case Event("open", counter) => goto(Open) using(counter + 1) replying(Emit("dest", DoorMoved(counter + 1)))
    }

    when(Open) {
      case Event("close", counter) => goto(Closed) using(counter + 1) replying(Emit("dest", DoorMoved(counter + 1)))
    }

    whenUnhandled {
      case Event(cmd, counter) => {
        stay replying(Emit("dest", DoorNotMoved("cannot %s door in state %s" format (cmd, stateName))))
      }
    }
  }

  class Destination(queue: java.util.Queue[Any]) extends Actor {
    def receive = {
      case event => queue.add(event)
    }
  }
}
