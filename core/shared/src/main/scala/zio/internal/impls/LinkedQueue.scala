/*
 * Copyright 2018-2024 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.internal

import zio.Chunk
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import scala.annotation.nowarn

private[zio] final class LinkedQueue[A] private (addMetrics: Boolean)
    extends MutableConcurrentQueue[A]
    with Serializable {

  /**
   * Public constructor to create a new LinkedQueue.
   *
   * Required for retrocompatibility. See https://github.com/zio/zio/pull/8784
   *
   * Use [[LinkedQueue.apply]] to use a different `addMetrics` value.
   */
  def this() = this(addMetrics = true)

  override final val capacity = Int.MaxValue

  private[this] val jucConcurrentQueue = new ConcurrentLinkedQueue[A]()
  /*
   * Using increment on AtomicLongs to provide metrics '''will''' have
   * performance implications. Having a better solution would be
   * desirable.
   */
  private[this] val enqueuedCounter = if (addMetrics) new AtomicLong(0) else null
  private[this] val dequeuedCounter = if (addMetrics) new AtomicLong(0) else null

  override def size(): Int = jucConcurrentQueue.size()

  override def enqueuedCount(): Long = if (enqueuedCounter ne null) enqueuedCounter.get() else 0L

  override def dequeuedCount(): Long = if (dequeuedCounter ne null) dequeuedCounter.get() else 0L

  override def offer(a: A): Boolean = {
    val success = jucConcurrentQueue.offer(a)
    if ((enqueuedCounter ne null) && success) enqueuedCounter.incrementAndGet()
    success
  }

  override def offerAll[A1 <: A](as: Iterable[A1]): Chunk[A1] = {
    import collection.JavaConverters._
    jucConcurrentQueue.addAll(as.asJavaCollection): @nowarn("msg=JavaConverters")
    if (enqueuedCounter ne null) enqueuedCounter.addAndGet(as.size.toLong)
    Chunk.empty
  }

  override def poll(default: A): A = {
    val polled = jucConcurrentQueue.poll()
    if (polled != null) {
      if (dequeuedCounter ne null) dequeuedCounter.incrementAndGet()
      polled
    } else default
  }

  override def isEmpty(): Boolean = jucConcurrentQueue.isEmpty

  override def isFull(): Boolean = false
}

object LinkedQueue {
  def apply[A](addMetrics: Boolean): LinkedQueue[A] = new LinkedQueue[A](addMetrics)
}
