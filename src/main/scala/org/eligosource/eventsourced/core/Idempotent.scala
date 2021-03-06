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

import akka.actor.Actor

/**
 * Makes event [[org.eligosource.eventsourced.core.Message]] receivers idempotent
 * based on message sequence number.
 *
 * Experimental.
 */
trait Idempotent extends Actor {
  var lastSequenceNr = 0L

  abstract override def receive = {
    case msg: Message => if (msg.sequenceNr > lastSequenceNr) {
      lastSequenceNr = msg.sequenceNr; super.receive(msg)
    } else { sender ! Ack }
    case msg => {
      super.receive(msg)
    }
  }
}
