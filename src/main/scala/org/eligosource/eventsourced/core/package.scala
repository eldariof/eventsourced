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
package org.eligosource.eventsourced

import akka.actor.ActorRef

package object core {
  private [eventsourced] val SkipAck: Long = -1L

  private [core] case class SetId(id: Int)

  /**
   * Sent to actors modified with [[org.eligosource.eventsourced.core.Emitter]]
   * for injecting a [[org.eligosource.eventsourced.core.Context]].
   */
  case class SetContext(context: Context)

  /**
   *  - Reply from [[org.eligosource.eventsourced.core.Eventsourced]] processors to indicate
   *    the successful write of an event [[org.eligosource.eventsourced.core.Message]] to a journal.
   *  - Reply from event [[org.eligosource.eventsourced.core.Message]] receivers to indicate
   *    the successful receipt of an event message.
   */
  case object Ack

  implicit def actorRef2ReceiverRef(processor: ActorRef) = {
    new ReceiverRef(processor)
  }

  // ------------------------------------------------------------
  //  Factories for special-purpose processors
  // ------------------------------------------------------------

  /**
   * Returns a decorating processor.
   *
   * @param target decorated target.
   */
  def decorator(target: ActorRef): Decorator with Eventsourced =
    new Decorator(target) with Eventsourced

  /**
   * Returns a mutlicast processor.
   *
   * @param targets multicast targets.
   */
  def multicast(targets: Seq[ActorRef]): Multicast with Eventsourced =
    new Multicast(targets) with Eventsourced with ForwardContext with ForwardMessage
}